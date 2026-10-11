package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.resolveUserId
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * M2/M3 — POST /api/v1/messages, GET /api/v1/me/messages, DELETE /api/v1/me/messages/{id}
 * (maps PHP message.php posting plus user.php?act=message_list / del_msg).
 * Posting is anonymous-friendly: an optional bearer token binds user_id and defaults the
 * display name to the login name, while anonymous:true pins it to "anonymous". Development
 * config publishes immediately (msg_status = 1). The list only returns the caller's own
 * top-level rows with the first reply; deletion removes the thread top and its replies in
 * one transaction and 404s for foreign or missing ids.
 */
@Serializable
data class CreateMessageRequest(
    val username: String = "",
    val email: String = "",
    val type: Int = 0,
    val title: String = "",
    val content: String = "",
    val anonymous: Boolean = false,
)

fun Route.messageWriteRoutes(svc: AppServices) {
    post("/messages") {
        val req = call.receive<CreateMessageRequest>()
        val content = req.content.trim()
        if (content.isEmpty() || content.toByteArray(Charsets.UTF_8).size > 2000) {
            throw validationError("content must be 1-2000 bytes", "content")
        }
        if (req.title.trim().toByteArray(Charsets.UTF_8).size > 200) throw validationError("title must be at most 200 bytes", "title")
        if (req.username.trim().toByteArray(Charsets.UTF_8).size > 60) throw validationError("username must be at most 60 bytes", "username")
        if (req.email.trim().toByteArray(Charsets.UTF_8).size > 60) throw validationError("email must be at most 60 bytes", "email")
        val boundUid = runCatching { resolveUserId(svc.db, call) }.getOrNull()
        val displayName = when {
            req.anonymous -> "anonymous"
            boundUid != null -> svc.db.read { c -> c.selectOne("SELECT user_name FROM ecs_users WHERE user_id = ?", listOf(boundUid)) { it.str("user_name") } } ?: "member"
            req.username.isNotBlank() -> req.username.trim()
            else -> "guest"
        }
        val msgId = svc.db.transaction { c ->
            c.insert(
                "INSERT INTO ecs_feedback(parent_id, user_id, user_name, user_email, msg_title, msg_type, msg_status, msg_content, msg_area) VALUES(0, ?, ?, ?, ?, ?, 1, ?, 1)",
                listOf(boundUid ?: 0L, displayName.take(60), req.email.trim().take(60), req.title.trim().take(200), req.type, content),
            )
        }
        val item = svc.db.read { c ->
            c.selectOne("SELECT msg_id, user_name, user_email, msg_title, msg_type, msg_content, msg_time FROM ecs_feedback WHERE msg_id = ?", listOf(msgId)) { rs -> messageJson(rs) }
        } ?: throw notFound("message vanished")
        call.respond(HttpStatusCode.Created, item)
    }

    get("/me/messages") {
        val uid = requireUserId(svc.db, call)
        val items = svc.db.read { c ->
            val rows = c.select(
                "SELECT msg_id, user_name, user_email, msg_title, msg_type, msg_content, msg_time, order_id, msg_status FROM ecs_feedback WHERE user_id = ? AND parent_id = 0 ORDER BY msg_id DESC",
                listOf(uid),
            ) { rs ->
                val msgId = rs.long("msg_id")
                val json = buildJsonObject {
                    put("msg_id", msgId)
                    put("username", rs.str("user_name"))
                    put("email", rs.str("user_email"))
                    put("title", rs.str("msg_title"))
                    put("type", rs.long("msg_type"))
                    put("content", rs.str("msg_content"))
                    put("msg_time", rs.long("msg_time"))
                    put("order_id", rs.long("order_id"))
                    put("status", rs.long("msg_status"))
                }
                msgId to json
            }
            JsonArray(
                rows.map { (msgId, json) ->
                    val reply = c.selectOne(
                        "SELECT user_name, msg_content, msg_time FROM ecs_feedback WHERE parent_id = ? ORDER BY msg_id LIMIT 1",
                        listOf(msgId),
                    ) { rs ->
                        buildJsonObject {
                            put("username", rs.str("user_name"))
                            put("content", rs.str("msg_content"))
                            put("msg_time", rs.long("msg_time"))
                        }
                    }
                    buildJsonObject {
                        for ((k, v) in json) put(k, v)
                        put("reply", reply ?: JsonNull)
                    }
                },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }

    delete("/me/messages/{id}") {
        val uid = requireUserId(svc.db, call)
        val msgId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("message not found")
        svc.db.transaction { c ->
            val owned = c.selectOne(
                "SELECT msg_id FROM ecs_feedback WHERE msg_id = ? AND user_id = ? AND parent_id = 0",
                listOf(msgId, uid),
            ) { it.long("msg_id") } ?: throw notFound("message not found")
            c.execute("DELETE FROM ecs_feedback WHERE parent_id = ?", listOf(owned))
            val deleted = c.execute("DELETE FROM ecs_feedback WHERE msg_id = ? AND user_id = ?", listOf(owned, uid))
            if (deleted != 1) throw notFound("message not found")
        }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun messageJson(rs: ResultSet) = buildJsonObject {
    put("msg_id", rs.long("msg_id"))
    put("username", rs.str("user_name"))
    put("email", rs.str("user_email"))
    put("title", rs.str("msg_title"))
    put("type", rs.long("msg_type"))
    put("content", rs.str("msg_content"))
    put("msg_time", rs.long("msg_time"))
}
