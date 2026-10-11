package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.db.strOrNull
import ecshop.http.conflict
import ecshop.http.notFound
import ecshop.http.requireUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — GET /api/v1/me/comments, DELETE /api/v1/me/comments/{id}
 * (maps PHP user.php?act=comment_list / del_cmt).
 * Returns the caller's own top-level comments on goods (comment_type = 0) or articles
 * (comment_type = 2), each annotated with the target name resolved from the owning table.
 * Deletion matches comment id AND bearer user id, so other users' comments are 404.
 */
fun Route.myCommentRoutes(svc: AppServices) {
    get("/me/comments") {
        val uid = requireUserId(svc.db, call)
        val items = svc.db.read { c ->
            val rows = c.select(
                "SELECT comment_id, comment_type, id_value, content, status, add_time FROM ecs_comment WHERE user_id = ? ORDER BY comment_id DESC",
                listOf(uid),
            ) { rs -> MyCommentRow(rs.long("comment_id"), rs.long("comment_type"), rs.long("id_value"), rs.str("content"), rs.long("status"), rs.long("add_time")) }
            JsonArray(
                rows.map { row ->
                    val targetName = when (row.type) {
                        0L -> c.selectOne("SELECT goods_name AS name FROM ecs_goods WHERE goods_id = ?", listOf(row.targetId)) { it.strOrNull("name") }
                        2L -> c.selectOne("SELECT title AS name FROM ecs_article WHERE article_id = ?", listOf(row.targetId)) { it.strOrNull("name") }
                        else -> null
                    }
                    buildJsonObject {
                        put("comment_id", row.id)
                        put("target_type", if (row.type == 2L) "article" else "goods")
                        put("target_id", row.targetId)
                        put("target_name", targetName)
                        put("content", row.content)
                        put("status", row.status)
                        put("add_time", row.addTime)
                    }
                },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }

    delete("/me/comments/{id}") {
        val uid = requireUserId(svc.db, call)
        val commentId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("comment not found")
        val deleted = svc.db.transaction { c ->
            c.execute("DELETE FROM ecs_comment WHERE comment_id = ? AND user_id = ?", listOf(commentId, uid))
        }
        if (deleted != 1) throw notFound("comment not found")
        call.respond(HttpStatusCode.NoContent)
    }
}

private data class MyCommentRow(val id: Long, val type: Long, val targetId: Long, val content: String, val status: Long, val addTime: Long)
