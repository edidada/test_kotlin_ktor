package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.conflict
import ecshop.http.listEnvelope
import ecshop.http.notFound
import ecshop.http.parsePaging
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * M2/M3 — GET and POST /api/v1/goods/{id}/comments (maps PHP comment.php read/write).
 * The list exposes only published product comments (comment_type = 0, status = 1).
 * Posting requires a bearer token: the user id and display name come from the session,
 * never from the request body, and the product must be visible or the write 404s.
 */
@Serializable
data class CreateCommentRequest(val content: String = "")

fun Route.commentsRoutes(svc: AppServices) {
    get("/goods/{id}/comments") {
        val goodsId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("goods not found")
        val paging = call.parsePaging()
        val visible = svc.db.read { c ->
            c.selectOne(
                "SELECT goods_id FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                listOf(goodsId),
            ) { it.long("goods_id") }
        } ?: throw notFound("goods not found")
        val body = svc.db.read { c ->
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_comment WHERE id_value = ? AND comment_type = 0 AND status = 1",
                listOf(visible),
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT comment_id, user_id, user_name, content, add_time FROM ecs_comment " +
                        "WHERE id_value = ? AND comment_type = 0 AND status = 1 ORDER BY comment_id DESC LIMIT ? OFFSET ?",
                    listOf(visible, paging.limit, paging.offset),
                ) { rs -> commentJson(rs) },
            )
            listEnvelope(paging.page, paging.pageSize, total, items)
        }
        call.respond(body)
    }

    post("/goods/{id}/comments") {
        val uid = requireUserId(svc.db, call)
        val goodsId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("goods not found")
        val req = call.receive<CreateCommentRequest>()
        val content = req.content.trim()
        if (content.isEmpty() || content.toByteArray(Charsets.UTF_8).size > 2000) {
            throw validationError("content must be 1-2000 bytes", "content")
        }
        val created = svc.db.transaction { c ->
            val goods = c.selectOne(
                "SELECT goods_name FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                listOf(goodsId),
            ) { it.str("goods_name") } ?: throw notFound("goods not found")
            val userName = c.selectOne("SELECT user_name FROM ecs_users WHERE user_id = ?", listOf(uid)) { it.str("user_name") }
                ?: throw conflict("user not found")
            val id = c.execute(
                "INSERT INTO ecs_comment(comment_type, id_value, user_id, user_name, content, status) VALUES(0, ?, ?, ?, ?, 1)",
                listOf(goodsId, uid, userName, content),
            )
            check(id == 1)
            goodsId
        }
        val item = svc.db.read { c ->
            c.selectOne(
                "SELECT comment_id, user_id, user_name, content, add_time FROM ecs_comment WHERE id_value = ? AND user_id = ? AND comment_type = 0 ORDER BY comment_id DESC LIMIT 1",
                listOf(created, uid),
            ) { rs -> commentJson(rs) }
        } ?: throw conflict("comment vanished")
        call.respond(HttpStatusCode.Created, item)
    }
}

internal fun commentJson(rs: ResultSet) = buildJsonObject {
    put("comment_id", rs.long("comment_id"))
    put("id", rs.long("comment_id"))
    put("user_id", rs.long("user_id"))
    put("user_name", rs.str("user_name"))
    put("content", rs.str("content"))
    put("add_time", rs.long("add_time"))
}
