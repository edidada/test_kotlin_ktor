package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.conflict
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.sqlite.SQLiteException

/**
 * M2 — GET /PATCH /api/v1/me (maps PHP user.php?act=profile / act_edit_profile).
 * Reads and updates the bearer user's credentials fields. Email/username uniqueness is
 * enforced by the DB constraints; violations map to 409.
 */
@Serializable
data class PatchMeRequest(val username: String? = null, val email: String? = null)

fun Route.profileRoutes(svc: AppServices) {
    get("/me") {
        val uid = requireUserId(svc.db, call)
        val body = svc.db.read { c -> profileJson(c, uid) ?: throw ecshop.http.notFound("user not found") }
        call.respond(body)
    }

    patch("/me") {
        val uid = requireUserId(svc.db, call)
        val body = call.receive<PatchMeRequest>()
        val username = body.username?.trim()?.takeIf { it.isNotEmpty() }
        val email = body.email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        if (username == null && email == null) throw validationError("username or email must be provided")
        if (username != null && username.length !in 1..60) throw validationError("username must be 1-60 characters", "username")
        if (email != null && !EMAIL_RE.matches(email)) throw validationError("email format is invalid", "email")
        try {
            svc.db.transaction { c ->
                if (username != null) {
                    c.execute("UPDATE ecs_users SET user_name = ? WHERE user_id = ?", listOf(username, uid))
                }
                if (email != null) {
                    c.execute("UPDATE ecs_users SET email = ? WHERE user_id = ?", listOf(email, uid))
                }
            }
        } catch (e: SQLiteException) {
            if (e.message?.contains("UNIQUE") == true) throw conflict("username or email already taken")
            throw e
        }
        val updated = svc.db.read { c -> profileJson(c, uid) }
        call.respond(updated!!)
    }
}

private fun profileJson(c: java.sql.Connection, uid: Long) =
    c.selectOne(
        "SELECT user_id, user_name, email, created_at FROM ecs_users WHERE user_id = ?",
        listOf(uid),
    ) { rs ->
        buildJsonObject {
            put("user_id", rs.long("user_id"))
            put("username", rs.str("user_name"))
            put("email", rs.str("email"))
            put("created_at", rs.long("created_at"))
        }
    }
