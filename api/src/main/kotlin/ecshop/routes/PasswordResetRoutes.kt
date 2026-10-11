package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.longOrNull
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.Passwords
import ecshop.http.conflict
import ecshop.http.randomHex
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
 * M2 — POST /api/v1/password-resets and POST /api/v1/password-resets/confirm
 * (maps PHP user.php?act=get_password / send_pwd_email / reset_password).
 * The request endpoint answers 202 for every syntactically valid email so accounts cannot be
 * enumerated. Reset tokens expire after one hour, are stored hashed, and the plaintext only
 * exists inside the outbox payload. Confirmation rewrites the password, revokes every session
 * and consumes the token in one transaction, guarded on the token hash to block replays.
 */
@Serializable
data class PasswordResetRequest(val email: String = "")

@Serializable
data class PasswordResetConfirmRequest(val token: String = "", val new_password: String = "")

private const val PWD_RESET_TTL_SECONDS = 3600L

fun Route.passwordResetRoutes(svc: AppServices) {
    post("/password-resets") {
        val body = call.receive<PasswordResetRequest>()
        if (!EMAIL_RE.matches(body.email)) throw validationError("email format is invalid", "email")
        val email = body.email.trim().lowercase()
        val token = randomHex(32)
        val hash = sha256Hex(token)
        val now = System.currentTimeMillis() / 1000L
        svc.db.transaction { c ->
            val uid = c.selectOne(
                "SELECT user_id FROM ecs_users WHERE email = ?",
                listOf(email),
            ) { it.long("user_id") }
            if (uid != null) {
                val replaced = c.execute(
                    "UPDATE ecs_password_reset_tokens SET token_hash = ?, expires_at = ?, created_at = ?, consumed_at = NULL WHERE user_id = ?",
                    listOf(hash, now + PWD_RESET_TTL_SECONDS, now, uid),
                )
                if (replaced == 0) {
                    c.execute(
                        "INSERT INTO ecs_password_reset_tokens(user_id, token_hash, expires_at) VALUES(?, ?, ?)",
                        listOf(uid, hash, now + PWD_RESET_TTL_SECONDS),
                    )
                }
                c.execute(
                    "INSERT INTO ecs_email_outbox(user_id, recipient, template_name, payload) VALUES(?, ?, 'password_reset', ?)",
                    listOf(uid, email, "{\"token\":\"$token\"}"),
                )
            }
        }
        call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", "accepted") })
    }

    post("/password-resets/confirm") {
        val body = call.receive<PasswordResetConfirmRequest>()
        if (!Regex("^[0-9a-f]{64}$").matches(body.token)) {
            throw validationError("token must be 64 lowercase hex characters", "token")
        }
        if (body.new_password.toByteArray(Charsets.UTF_8).size !in 8..1024) {
            throw validationError("new_password must be 8-1024 bytes", "new_password")
        }
        val hash = sha256Hex(body.token)
        val newHash = Passwords.hash(body.new_password)
        val now = System.currentTimeMillis() / 1000L
        svc.db.transaction { c ->
            val row = c.selectOne(
                "SELECT user_id, expires_at, consumed_at FROM ecs_password_reset_tokens WHERE token_hash = ?",
                listOf(hash),
            ) { rs -> Triple(rs.long("user_id"), rs.long("expires_at"), rs.longOrNull("consumed_at")) }
            if (row == null || row.third != null || row.second < now) throw conflict("token is invalid or expired")
            val consumed = c.execute(
                "UPDATE ecs_password_reset_tokens SET consumed_at = ? WHERE token_hash = ? AND consumed_at IS NULL",
                listOf(now, hash),
            )
            if (consumed != 1) throw conflict("token is invalid or expired")
            c.execute("UPDATE ecs_users SET password_hash = ? WHERE user_id = ?", listOf(newHash, row.first))
            c.execute("DELETE FROM ecs_sessions WHERE user_id = ?", listOf(row.first))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
