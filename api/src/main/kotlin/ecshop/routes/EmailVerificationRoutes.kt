package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.longOrNull
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.conflict
import ecshop.http.randomHex
import ecshop.http.requireUserId
import ecshop.http.sha256Hex
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — POST /api/v1/me/email-verifications and POST /api/v1/email-verifications/confirm
 * (maps PHP user.php?act=send_hash_mail / validate_email).
 * The plaintext token never touches the database or an HTTP response: only its SHA-256 hash
 * is stored, the plaintext goes into the email outbox for a separate mailer. Requesting a new
 * verification atomically replaces the old token. Confirmation consumes the token exactly once
 * under the DB clock; every failure mode collapses to 409 to avoid leaking state.
 */
@Serializable
data class ConfirmEmailRequest(val token: String = "")

private const val EMAIL_TOKEN_TTL_SECONDS = 24L * 3600L

fun Route.emailVerificationRoutes(svc: AppServices) {
    post("/me/email-verifications") {
        val uid = requireUserId(svc.db, call)
        val token = randomHex(32)
        val hash = sha256Hex(token)
        val now = System.currentTimeMillis() / 1000L
        svc.db.transaction { c ->
            val email = c.selectOne(
                "SELECT email FROM ecs_users WHERE user_id = ?",
                listOf(uid),
            ) { it.str("email") } ?: throw conflict("user not found")
            val replaced = c.execute(
                "UPDATE ecs_email_verification_tokens SET token_hash = ?, expires_at = ?, created_at = ?, consumed_at = NULL WHERE user_id = ?",
                listOf(hash, now + EMAIL_TOKEN_TTL_SECONDS, now, uid),
            )
            if (replaced == 0) {
                c.execute(
                    "INSERT INTO ecs_email_verification_tokens(user_id, token_hash, expires_at) VALUES(?, ?, ?)",
                    listOf(uid, hash, now + EMAIL_TOKEN_TTL_SECONDS),
                )
            }
            c.execute(
                "INSERT INTO ecs_email_outbox(user_id, recipient, template_name, payload) VALUES(?, ?, 'verify_email', ?)",
                listOf(uid, email, "{\"token\":\"$token\"}"),
            )
        }
        call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", "queued") })
    }

    post("/email-verifications/confirm") {
        val body = call.receive<ConfirmEmailRequest>()
        if (!Regex("^[0-9a-f]{64}$").matches(body.token)) {
            throw validationError("token must be 64 lowercase hex characters", "token")
        }
        val hash = sha256Hex(body.token)
        val now = System.currentTimeMillis() / 1000L
        svc.db.transaction { c ->
            val row = c.selectOne(
                "SELECT user_id, expires_at, consumed_at FROM ecs_email_verification_tokens WHERE token_hash = ?",
                listOf(hash),
            ) { rs -> Triple(rs.long("user_id"), rs.long("expires_at"), rs.longOrNull("consumed_at")) }
            if (row == null || row.third != null || row.second < now) throw conflict("token is invalid or expired")
            val consumed = c.execute(
                "UPDATE ecs_email_verification_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL",
                listOf(now, hash),
            )
            if (consumed != 1) throw conflict("token is invalid or expired")
            val inserted = c.execute(
                "INSERT OR IGNORE INTO ecs_email_verified_users(user_id, verified_at) VALUES(?, ?)",
                listOf(row.first, now),
            )
            if (inserted == 0) {
                c.execute("UPDATE ecs_email_verified_users SET verified_at = ? WHERE user_id = ?", listOf(now, row.first))
            }
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
