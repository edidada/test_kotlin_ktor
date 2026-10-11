package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.str
import ecshop.db.selectOne
import ecshop.http.Passwords
import ecshop.http.conflict
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import kotlinx.serialization.Serializable

/**
 * M2 — PATCH /api/v1/me/password (maps PHP user.php?act=act_edit_password).
 * Verifies the current password, then atomically swaps the hash conditioned on the old hash
 * (no TOCTOU between check and write) and revokes every session of that user in the same
 * transaction. 204 on success; wrong/changed password 409; invalid session 401.
 */
@Serializable
data class ChangePasswordRequest(val current_password: String = "", val new_password: String = "")

fun Route.passwordRoutes(svc: AppServices) {
    patch("/me/password") {
        val uid = requireUserId(svc.db, call)
        val body = call.receive<ChangePasswordRequest>()
        if (body.new_password.toByteArray(Charsets.UTF_8).size !in 8..1024) {
            throw validationError("new_password must be 8-1024 bytes", "new_password")
        }
        val oldHash = svc.db.read { c ->
            c.selectOne(
                "SELECT password_hash FROM ecs_users WHERE user_id = ?",
                listOf(uid),
            ) { it.str("password_hash") }
        } ?: throw ecshop.http.notFound("user not found")
        if (!Passwords.verify(body.current_password, oldHash)) {
            throw conflict("current password is incorrect")
        }
        val newHash = Passwords.hash(body.new_password)
        svc.db.transaction { c ->
            val updated = c.execute(
                "UPDATE ecs_users SET password_hash = ? WHERE user_id = ? AND password_hash = ?",
                listOf(newHash, uid, oldHash),
            )
            if (updated != 1) throw conflict("password changed concurrently")
            c.execute("DELETE FROM ecs_sessions WHERE user_id = ?", listOf(uid))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
