package ecshop.routes

import ecshop.AppServices
import ecshop.db.select
import ecshop.db.long
import ecshop.http.notFound
import ecshop.http.queryString
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/compare?goods_ids=12,14 (maps PHP compare.php?goods[]=).
 * 2..4 distinct positive ids, returned in input order. Any invisible goods yields 404 so
 * comparisons never leak off-sale or deleted products.
 */
fun Route.compareRoutes(svc: AppServices) {
    get("/compare") {
        val raw = call.queryString("goods_ids")
            ?: throw validationError("goods_ids is required", "goods_ids")
        val ids = raw.split(',').map { part ->
            part.trim().toLongOrNull()?.takeIf { it > 0 }
                ?: throw validationError("goods_ids must be positive integers", "goods_ids")
        }
        if (ids.size < 2 || ids.size > 4) {
            throw validationError("goods_ids must contain 2 to 4 ids", "goods_ids")
        }
        if (ids.distinct().size != ids.size) {
            throw validationError("goods_ids must not contain duplicates", "goods_ids")
        }
        val placeholders = ids.joinToString(",") { "?" }
        val body = svc.db.read { c ->
            val rows = c.select(
                "$GOODS_LIST_SELECT FROM ecs_goods WHERE goods_id IN ($placeholders) AND $GOODS_VISIBLE",
                ids,
            ) { rs -> rs.long("goods_id") to goodsListJson(rs) }
            val found = rows.associateBy({ it.first }, { it.second })
            if (ids.any { it !in found }) throw notFound("one or more goods are not comparable")
            buildJsonObject {
                put("items", JsonArray(ids.map { found.getValue(it) }))
            }
        }
        call.respond(body)
    }
}
