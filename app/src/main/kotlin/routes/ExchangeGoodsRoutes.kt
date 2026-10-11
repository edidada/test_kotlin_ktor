package ecshop.routes

import ecshop.AppServices
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.parsePaging
import ecshop.http.pathLong
import ecshop.http.queryLong
import ecshop.http.queryString
import ecshop.http.resolveOrder
import ecshop.http.resolveSort
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/exchange-goods and /exchange-goods/{id} (maps PHP exchange.php act=list/view).
 * Only exchange-enabled goods whose base goods stay publicly sellable are returned.
 * category_id includes the category subtree; sort allows goods_id|exchange_integral|last_update.
 */
private val EXCHANGE_SORTS = mapOf(
    "goods_id" to "eg.goods_id",
    "exchange_integral" to "eg.exchange_integral",
    "last_update" to "g.last_update",
)

fun Route.exchangeGoodsRoutes(svc: AppServices) {
    get("/exchange-goods") {
        val categoryId = call.queryLong("category_id")
        val integralMin = call.queryLong("integral_min")
        val integralMax = call.queryLong("integral_max")
        val sort = resolveSort(call.queryString("sort"), EXCHANGE_SORTS, "eg.last_update", "sort")
        val order = resolveOrder(call.queryString("order"), "desc")
        val paging = call.parsePaging()

        val where = StringBuilder("eg.is_exchange = 1 AND g.is_on_sale = 1 AND g.is_delete = 0")
        val params = ArrayList<Any?>()
        var subtreeIds: List<Long> = emptyList()
        val body = svc.db.read { c ->
            if (categoryId != null) {
                subtreeIds = c.select(
                    "WITH RECURSIVE cat_tree(id) AS (" +
                        "SELECT cat_id FROM ecs_category WHERE cat_id = ? UNION ALL " +
                        "SELECT ch.cat_id FROM ecs_category ch JOIN cat_tree t ON ch.parent_id = t.id" +
                        ") SELECT id FROM cat_tree",
                    listOf(categoryId),
                ) { it.long("id") }
                where.append(" AND g.cat_id IN (").append(subtreeIds.joinToString(",") { "?" }).append(")")
                params.addAll(subtreeIds)
            }
            if (integralMin != null) {
                where.append(" AND eg.exchange_integral >= ?")
                params.add(integralMin)
            }
            if (integralMax != null) {
                where.append(" AND eg.exchange_integral <= ?")
                params.add(integralMax)
            }
            val whereSql = where.toString()
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_exchange_goods eg JOIN ecs_goods g ON g.goods_id = eg.goods_id " +
                    "WHERE $whereSql",
                params,
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT eg.goods_id, eg.exchange_integral, eg.is_hot, g.goods_name, g.goods_thumb, " +
                        "g.shop_price, g.market_price, g.promote_price, g.promote_start_date, g.promote_end_date, " +
                        "g.is_promote, g.goods_number, g.last_update, g.cat_id, g.brand_id, g.goods_sn, g.add_time, " +
                        "g.is_best, g.is_new, g.is_hot AS goods_is_hot, g.is_delete " +
                        "FROM ecs_exchange_goods eg JOIN ecs_goods g ON g.goods_id = eg.goods_id " +
                        "WHERE $whereSql ORDER BY $sort $order, eg.goods_id LIMIT ? OFFSET ?",
                    params + listOf(paging.limit, paging.offset),
                ) { rs -> exchangeJson(rs) },
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

    get("/exchange-goods/{id}") {
        val goodsId = call.pathLong("id")
        val body = svc.db.read { c ->
            c.selectOne(
                "SELECT eg.goods_id, eg.exchange_integral, eg.is_hot, g.goods_name, g.goods_sn, g.cat_id, " +
                    "g.brand_id, g.goods_brief, g.goods_thumb, g.shop_price, g.market_price, g.promote_price, " +
                    "g.promote_start_date, g.promote_end_date, g.is_promote, g.goods_number, g.last_update, " +
                    "g.is_best, g.is_new, g.is_hot, g.add_time, g.goods_desc " +
                    "FROM ecs_exchange_goods eg JOIN ecs_goods g ON g.goods_id = eg.goods_id " +
                    "WHERE eg.goods_id = ? AND eg.is_exchange = 1 AND g.is_on_sale = 1 AND g.is_delete = 0",
                listOf(goodsId),
            ) { rs ->
                buildJsonObject {
                    put("goods", goodsListJson(rs))
                    put("description", rs.str("goods_desc"))
                    put("exchange_integral", rs.long("exchange_integral"))
                    put("is_exchange_hot", rs.int("is_hot") == 1)
                }
            } ?: throw notFound("exchange goods not found")
        }
        call.respond(body)
    }
}

private fun exchangeJson(rs: java.sql.ResultSet) = buildJsonObject {
    put("goods_id", rs.long("goods_id"))
    put("name", rs.str("goods_name"))
    put("goods_sn", rs.str("goods_sn"))
    put("thumb", rs.str("goods_thumb"))
    put("price", displayPriceRs(rs))
    put("exchange_integral", rs.long("exchange_integral"))
    put("is_hot", rs.int("is_hot") == 1)
    put("stock", rs.long("goods_number"))
    put("last_update", rs.long("last_update"))
}
