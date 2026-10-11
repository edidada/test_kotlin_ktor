package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.Passwords
import ecshop.http.createSession
import ecshop.http.destroySession
import ecshop.http.unauthorized
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
 * M2 — POST /api/v1/auth/login and /auth/logout (maps PHP user.php?act=act_login / logout).
 * Login issues a random bearer token; only its SHA-256 hash is persisted. Logout revokes the
 * current session and returns 204.
 */
@Serializable
data class LoginRequest(val username: String = "", val password: String = "")

fun Route.loginRoutes(svc: AppServices) {
    post("/auth/login") {
        val body = call.receive<LoginRequest>()
        val username = body.username.trim()
        if (username.isEmpty() || body.password.isEmpty()) {
            throw unauthorized("invalid credentials")
        }
        val row = svc.db.read { c ->
            c.selectOne(
                "SELECT user_id, user_name, password_hash FROM ecs_users WHERE user_name = ?",
                listOf(username),
            ) { rs -> Triple(rs.long("user_id"), rs.str("user_name"), rs.str("password_hash")) }
        } ?: throw unauthorized("invalid credentials")
        if (!Passwords.verify(body.password, row.third)) throw unauthorized("invalid credentials")
        val token = createSession(svc.db, row.first)
        call.respond(
            buildJsonObject {
                put("user_id", row.first)
                put("username", row.second)
                put("access_token", token)
            },
        )
    }

    post("/auth/logout") {
        // Require a live session before revoking it.
        ecshop.http.requireUserId(svc.db, call)
        destroySession(svc.db, call)
        call.respond(HttpStatusCode.NoContent)
    }
}
