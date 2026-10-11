package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.parsePaging
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/messages (maps PHP message.php display). Front-office, published
 * top-level messages (msg_area=1, msg_status=1) with their first reply.
 */
fun Route.messageListRoutes(svc: AppServices) {
    get("/messages") {
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_feedback WHERE msg_area = 1 AND msg_status = 1 AND parent_id = 0",
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT msg_id, user_name, user_email, msg_type, msg_title, msg_content, msg_time, order_id " +
                        "FROM ecs_feedback WHERE msg_area = 1 AND msg_status = 1 AND parent_id = 0 " +
                        "ORDER BY msg_id DESC LIMIT ? OFFSET ?",
                    listOf(paging.limit, paging.offset),
                ) { rs ->
                    val msgId = rs.long("msg_id")
                    val reply = c.selectOne(
                        "SELECT user_name, msg_content, msg_time FROM ecs_feedback " +
                            "WHERE parent_id = ? ORDER BY msg_id LIMIT 1",
                        listOf(msgId),
                    ) { r ->
                        buildJsonObject {
                            put("user_name", r.str("user_name"))
                            put("content", r.str("msg_content"))
                            put("msg_time", r.long("msg_time"))
                        }
                    }
                    buildJsonObject {
                        put("msg_id", msgId)
                        put("username", rs.str("user_name"))
                        put("email", rs.str("user_email"))
                        put("type", rs.long("msg_type"))
                        put("title", rs.str("msg_title"))
                        put("content", rs.str("msg_content"))
                        put("msg_time", rs.long("msg_time"))
                        put("order_id", rs.long("order_id"))
                        put("reply", reply ?: kotlinx.serialization.json.JsonNull)
                    }
                },
            )
            buildJsonObject {
                put("page", paging.page)
                put("page_size", paging.pageSize)
                put("total", total)
                put("items", items)
            }
        }
        call.respond(body)
    }
}
