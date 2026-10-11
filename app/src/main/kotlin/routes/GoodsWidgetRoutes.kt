package ecshop.routes

import ecshop.AppServices
import ecshop.db.select
import ecshop.http.parsePaging
import ecshop.http.queryLong
import ecshop.http.queryString
import ecshop.http.resolveSort
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * M4 — GET /goods-widget.js (maps PHP goods_script.php).
 * Public goods widget served as JavaScript assigning window.cppEcshopGoodsWidget.
 * intro_type is whitelisted; goods_num is capped at 50. The legacy
 * type=collection&u=<any user id> mode leaks other users' favorites and is deliberately
 * not implemented - logged-in users have /api/v1/me/favorites.
 */
private val WIDGET_INTRO_TYPES = mapOf(
    "is_best" to "is_best",
    "is_new" to "is_new",
    "is_hot" to "is_hot",
    "is_promote" to "is_promote",
    "is_random" to "is_random",
)

fun Route.goodsWidgetRoutes(svc: AppServices) {
    get("/goods-widget.js") {
        val catId = call.queryLong("cat_id")
        val brandId = call.queryLong("brand_id")
        val introType = call.queryString("intro_type")
        val sort = if (introType != null) {
            resolveSort(introType, WIDGET_INTRO_TYPES, "is_random")
        } else {
            null
        }
        val goodsNum = (call.queryString("goods_num")?.toLongOrNull() ?: 10L).coerceIn(1L, 50L)

        val where = StringBuilder("$GOODS_VISIBLE")
        val params = ArrayList<Any?>()
        if (catId != null) {
            where.append(" AND cat_id = ?")
            params.add(catId)
        }
        if (brandId != null) {
            where.append(" AND brand_id = ?")
            params.add(brandId)
        }
        if (sort != null && sort != "is_random") {
            where.append(" AND $sort = 1")
        }
        val order = if (sort == "is_random") "RANDOM()" else "sort_order, goods_id"

        val items = svc.db.read { c ->
            JsonArray(
                c.select(
                    "$GOODS_LIST_SELECT FROM ecs_goods WHERE $where ORDER BY $order LIMIT ?",
                    params + listOf(goodsNum),
                ) { rs -> goodsListJson(rs) },
            )
        }
        val payload = buildJsonObject {
            put("items", items)
        }
        call.respondText(
            "window.cppEcshopGoodsWidget = ${Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)};\n",
            ContentType("application", "javascript"),
        )
    }
}
