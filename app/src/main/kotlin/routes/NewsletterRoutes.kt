package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.longOrNull
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.conflict
import ecshop.http.randomHex
import ecshop.http.sha256Hex
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2/M5 — POST/DELETE /api/v1/newsletter-subscriptions and the two .../confirm endpoints
 * (maps PHP user.php?act=email_list with its four jobs).
 * Every request action answers 202 regardless of the current row state so mailbox existence
 * cannot be probed. Tokens are stored only as SHA-256 hashes (24-hour expiry); the plaintext
 * travels exclusively through the email outbox. Confirmations are single atomic
 * update/delete statements conditioned on action + expiry, so each token is usable once and
 * any mismatch collapses to 409.
 */
@Serializable
data class NewsletterEmailRequest(val email: String = "")

@Serializable
data class NewsletterConfirmRequest(val token: String = "")

private const val NEWSLETTER_TTL_SECONDS = 24L * 3600L

fun Route.newsletterRoutes(svc: AppServices) {
    post("/newsletter-subscriptions") {
        val email = parseEmail(call.receive<NewsletterEmailRequest>().email)
        requestNewsletter(svc, call, email, action = "subscribe")
    }

    delete("/newsletter-subscriptions") {
        val email = parseEmail(call.receive<NewsletterEmailRequest>().email)
        requestNewsletter(svc, call, email, action = "unsubscribe")
    }

    post("/newsletter-subscriptions/confirm") {
        val hash = parseConfirmToken(call)
        val now = System.currentTimeMillis() / 1000L
        val updated = svc.db.transaction { c ->
            c.execute(
                "UPDATE ecs_email_list SET status = 1, pending_action = '', token_hash = NULL, token_expires_at = NULL, updated_at = ? " +
                    "WHERE token_hash = ? AND pending_action = 'subscribe' AND token_expires_at > ?",
                listOf(now, hash, now),
            )
        }
        if (updated != 1) throw conflict("token is invalid, expired or already used")
        call.respond(HttpStatusCode.NoContent)
    }

    post("/newsletter-unsubscriptions/confirm") {
        val hash = parseConfirmToken(call)
        val now = System.currentTimeMillis() / 1000L
        val deleted = svc.db.transaction { c ->
            c.execute(
                "DELETE FROM ecs_email_list WHERE token_hash = ? AND pending_action = 'unsubscribe' AND token_expires_at > ?",
                listOf(hash, now),
            )
        }
        if (deleted != 1) throw conflict("token is invalid, expired or already used")
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun parseEmail(raw: String): String {
    val email = raw.trim().lowercase()
    if (!EMAIL_RE.matches(email)) throw validationError("email format is invalid", "email")
    return email
}

private suspend fun parseConfirmToken(call: io.ktor.server.application.ApplicationCall): String {
    val body = call.receive<NewsletterConfirmRequest>()
    if (!Regex("^[0-9a-f]{64}$").matches(body.token)) {
        throw validationError("token must be 64 lowercase hex characters", "token")
    }
    return sha256Hex(body.token)
}

private suspend fun requestNewsletter(svc: AppServices, call: io.ktor.server.application.ApplicationCall, email: String, action: String) {
    val token = randomHex(32)
    val hash = sha256Hex(token)
    val now = System.currentTimeMillis() / 1000L
    svc.db.transaction { c ->
        val row = c.selectOne(
            "SELECT status FROM ecs_email_list WHERE email = ?",
            listOf(email),
        ) { it.longOrNull("status") }
        val alreadyTarget = when (action) {
            "subscribe" -> row != null && row == 1L
            else -> row == null || row == 0L
        }
        if (!alreadyTarget) {
            c.execute(
                "INSERT INTO ecs_email_list(email, status, token_hash, pending_action, token_expires_at, updated_at) VALUES(?, 0, ?, ?, ?, ?) " +
                    "ON CONFLICT(email) DO UPDATE SET status = 0, token_hash = excluded.token_hash, pending_action = excluded.pending_action, token_expires_at = excluded.token_expires_at, updated_at = excluded.updated_at",
                listOf(email, hash, action, now + NEWSLETTER_TTL_SECONDS, now),
            )
            c.execute(
                "INSERT INTO ecs_email_outbox(user_id, recipient, template_name, payload) VALUES(NULL, ?, ?, ?)",
                listOf(email, if (action == "subscribe") "newsletter_subscribe" else "newsletter_unsubscribe", "{\"token\":\"$token\"}"),
            )
        }
    }
    call.respond(HttpStatusCode.Accepted, buildJsonObject { put("status", "accepted") })
}
