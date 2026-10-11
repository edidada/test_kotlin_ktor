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
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet
import org.sqlite.SQLiteException

/**
 * M2 — GET/POST /api/v1/me/favorites, PATCH/DELETE /api/v1/me/favorites/{id}
 * (maps PHP user.php?act=collection_list / collect / add_to_attention / del_attention /
 * delete_collection). Collecting is restricted to sellable goods and the UNIQUE(user_id,
 * goods_id) key answers duplicate posts with 409. Attention updates and deletes always
 * carry the owner id in the WHERE clause, so foreign rows look like missing rows (404).
 */
@Serializable
data class AddFavoriteRequest(val goods_id: Long = 0)

@Serializable
data class AttentionRequest(val attention: Boolean)

private const val FAV_COLS =
    "SELECT cg.rec_id, cg.goods_id, cg.add_time, cg.is_attention, g.goods_name, g.is_on_sale, g.is_delete, " +
        "g.shop_price, g.promote_price, g.promote_start_date, g.promote_end_date, g.is_promote "
private const val FAV_FROM = "FROM ecs_collect_goods cg JOIN ecs_goods g ON g.goods_id = cg.goods_id "

fun Route.favoritesRoutes(svc: AppServices) {
    get("/me/favorites") {
        val uid = requireUserId(svc.db, call)
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_collect_goods WHERE user_id = ?",
                listOf(uid),
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(FAV_COLS + FAV_FROM + "WHERE cg.user_id = ? ORDER BY cg.rec_id DESC LIMIT ? OFFSET ?", listOf(uid, paging.limit, paging.offset)) { rs ->
                    favoriteJson(rs)
                },
            )
            listEnvelope(paging.page, paging.pageSize, total, items)
        }
        call.respond(body)
    }

    post("/me/favorites") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<AddFavoriteRequest>()
        if (req.goods_id <= 0) throw ecshop.http.validationError("goods_id must be positive", "goods_id")
        try {
            svc.db.transaction { c ->
                val goodsId = c.selectOne(
                    "SELECT goods_id FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                    listOf(req.goods_id),
                ) { it.long("goods_id") } ?: throw notFound("goods not found")
                c.execute("INSERT INTO ecs_collect_goods(user_id, goods_id) VALUES(?, ?)", listOf(uid, goodsId))
            }
        } catch (e: SQLiteException) {
            if (e.message?.contains("UNIQUE") == true) throw conflict("goods already favorited")
            throw e
        }
        val item = svc.db.read { c ->
            c.selectOne(FAV_COLS + FAV_FROM + "WHERE cg.user_id = ? AND cg.goods_id = ? ORDER BY cg.rec_id DESC LIMIT 1", listOf(uid, req.goods_id)) { rs -> favoriteJson(rs) }
        } ?: throw conflict("favorite vanished")
        call.respond(HttpStatusCode.Created, item)
    }

    patch("/me/favorites/{id}") {
        val uid = requireUserId(svc.db, call)
        val recId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("favorite not found")
        val req = call.receive<AttentionRequest>()
        val updated = svc.db.transaction { c ->
            c.execute(
                "UPDATE ecs_collect_goods SET is_attention = ? WHERE rec_id = ? AND user_id = ?",
                listOf(if (req.attention) 1 else 0, recId, uid),
            )
        }
        if (updated != 1) throw notFound("favorite not found")
        val item = svc.db.read { c ->
            c.selectOne(FAV_COLS + FAV_FROM + "WHERE cg.rec_id = ? AND cg.user_id = ?", listOf(recId, uid)) { rs -> favoriteJson(rs) }
        } ?: throw notFound("favorite not found")
        call.respond(item)
    }

    delete("/me/favorites/{id}") {
        val uid = requireUserId(svc.db, call)
        val recId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("favorite not found")
        val deleted = svc.db.transaction { c ->
            c.execute("DELETE FROM ecs_collect_goods WHERE rec_id = ? AND user_id = ?", listOf(recId, uid))
        }
        if (deleted != 1) throw notFound("favorite not found")
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun favoriteJson(rs: ResultSet) = buildJsonObject {
    put("favorite_id", rs.long("rec_id"))
    put("id", rs.long("rec_id"))
    put("goods_id", rs.long("goods_id"))
    put("name", rs.str("goods_name"))
    put("price", displayPriceRs(rs))
    put("attention", rs.long("is_attention") == 1L)
    put("on_sale", rs.long("is_on_sale") == 1L)
    put("add_time", rs.long("add_time"))
}
