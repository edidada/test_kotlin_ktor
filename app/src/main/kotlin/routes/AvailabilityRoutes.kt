package ecshop.routes

import ecshop.AppServices
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — POST /api/v1/auth/availability/username and /availability/email
 * (maps PHP user.php?act=is_registered / act=check_email).
 * Advisory lookups only; registration still relies on DB unique constraints as the final
 * race guard, so TOCTOU here is harmless by design.
 */
@Serializable
data class UsernameAvailabilityRequest(val username: String = "")

@Serializable
data class EmailAvailabilityRequest(val email: String = "")

fun Route.availabilityRoutes(svc: AppServices) {
    post("/auth/availability/username") {
        val body = call.receive<UsernameAvailabilityRequest>()
        val username = body.username.trim()
        if (username.isEmpty()) throw validationError("username must not be empty", "username")
        val taken = svc.db.read { c ->
            c.selectOne(
                "SELECT user_name FROM ecs_users WHERE user_name = ?",
                listOf(username),
            ) { it.str("user_name") } != null
        }
        call.respond(buildJsonObject { put("available", !taken) })
    }

    post("/auth/availability/email") {
        val body = call.receive<EmailAvailabilityRequest>()
        val email = body.email.trim().lowercase()
        if (!EMAIL_RE.matches(email)) throw validationError("email format is invalid", "email")
        val taken = svc.db.read { c ->
            c.selectOne(
                "SELECT email FROM ecs_users WHERE email = ?",
                listOf(email),
            ) { it.str("email") } != null
        }
        call.respond(buildJsonObject { put("available", !taken) })
    }
}
