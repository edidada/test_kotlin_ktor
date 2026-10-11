package ecshop.routes

import ecshop.AppServices
import ecshop.db.insert
import ecshop.http.Passwords
import ecshop.http.conflict
import ecshop.http.createSession
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
 * M2 — POST /api/v1/auth/register (maps PHP user.php?act=act_register).
 * Server-side validation of length/uniqueness/email format; PBKDF2-SHA256 hash with random
 * salt is the only thing stored. Returns 201 with a fresh bearer token.
 */
@Serializable
data class RegisterRequest(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val agreement_accepted: Boolean = false,
)

fun Route.registerRoutes(svc: AppServices) {
    post("/auth/register") {
        val body = call.receive<RegisterRequest>()
        val username = body.username.trim()
        val email = body.email.trim().lowercase()
        if (username.length !in 1..60) throw validationError("username must be 1-60 characters", "username")
        if (!EMAIL_RE.matches(email)) throw validationError("email format is invalid", "email")
        if (body.password.toByteArray(Charsets.UTF_8).size !in 8..1024) {
            throw validationError("password must be 8-1024 bytes", "password")
        }
        if (!body.agreement_accepted) throw validationError("agreement must be accepted", "agreement_accepted")

        val userId = try {
            svc.db.transaction { c ->
                val id = c.insert(
                    "INSERT INTO ecs_users(user_name, email, password_hash) VALUES(?, ?, ?)",
                    listOf(username, email, Passwords.hash(body.password)),
                )
                // New users start with a zero balance ledger row.
                c.insert(
                    "INSERT OR IGNORE INTO ecs_account_balance(user_id) VALUES(?)",
                    listOf(id),
                )
                id
            }
        } catch (e: org.sqlite.SQLiteException) {
            if (e.message?.contains("UNIQUE") == true) {
                throw conflict("username or email already registered")
            }
            throw e
        }
        val token = createSession(svc.db, userId)
        call.respond(
            HttpStatusCode.Created,
            buildJsonObject {
                put("user_id", userId)
                put("username", username)
                put("access_token", token)
            },
        )
    }
}

internal val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
