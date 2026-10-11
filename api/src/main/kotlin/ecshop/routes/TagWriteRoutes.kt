package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — POST /api/v1/goods/{id}/tags, GET/DELETE /api/v1/me/tags
 * (maps PHP user.php?act=add_tag / tag_list / act_del_tag).
 * A tag submission carries 1-10 comma-separated words (max 255 bytes each). Writes are
 * restricted to sellable goods and to the bearer user id; the same word on the same goods
 * by the same user is ignored via NOT EXISTS instead of duplicating rows. DELETE /me/tags
 * removes only the caller's own identical words and is idempotent (always 204).
 */
@Serializable
data class AddTagRequest(val tag: String = "")

@Serializable
data class DeleteTagRequest(val tag: String = "")

fun Route.tagWriteRoutes(svc: AppServices) {
    post("/goods/{id}/tags") {
        val uid = requireUserId(svc.db, call)
        val goodsId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("goods not found")
        val req = call.receive<AddTagRequest>()
        val words = req.tag.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 10) throw validationError("tag must contain 1-10 comma-separated words", "tag")
        if (words.any { it.toByteArray(Charsets.UTF_8).size > 255 }) throw validationError("each tag word must be at most 255 bytes", "tag")
        val stats = svc.db.transaction { c ->
            val goods = c.selectOne(
                "SELECT goods_id FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                listOf(goodsId),
            ) { it.long("goods_id") } ?: throw notFound("goods not found")
            for (word in words.distinct()) {
                c.execute(
                    "INSERT INTO ecs_tag(user_id, goods_id, tag_words) SELECT ?, ?, ? WHERE NOT EXISTS (SELECT 1 FROM ecs_tag WHERE user_id = ? AND goods_id = ? AND tag_words = ?)",
                    listOf(uid, goods, word, uid, goods, word),
                )
            }
            JsonArray(
                c.select(
                    "SELECT tag_words, COUNT(*) AS cnt FROM ecs_tag WHERE goods_id = ? GROUP BY tag_words ORDER BY cnt DESC, tag_words",
                    listOf(goods),
                ) { rs ->
                    buildJsonObject {
                        put("tag", rs.str("tag_words"))
                        put("count", rs.long("cnt"))
                    }
                },
            )
        }
        call.respond(HttpStatusCode.Created, buildJsonObject { put("items", stats) })
    }

    get("/me/tags") {
        val uid = requireUserId(svc.db, call)
        val items = svc.db.read { c ->
            JsonArray(
                c.select(
                    "SELECT tag_id, goods_id, tag_words FROM ecs_tag WHERE user_id = ? ORDER BY tag_id DESC",
                    listOf(uid),
                ) { rs ->
                    buildJsonObject {
                        put("tag_id", rs.long("tag_id"))
                        put("goods_id", rs.long("goods_id"))
                        put("tag", rs.str("tag_words"))
                    }
                },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }

    delete("/me/tags") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<DeleteTagRequest>()
        val word = req.tag.trim()
        if (word.isEmpty()) throw validationError("tag must not be empty", "tag")
        svc.db.transaction { c ->
            c.execute("DELETE FROM ecs_tag WHERE user_id = ? AND tag_words = ?", listOf(uid, word))
        }
        call.respond(HttpStatusCode.NoContent)
    }
}
