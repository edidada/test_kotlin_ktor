package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.escapeLike
import ecshop.http.parsePaging
import ecshop.http.queryLong
import ecshop.http.queryString
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/quotation (maps PHP quotation.php?act=print_quotation).
 * One row per SKU (goods_products) with base price and real SKU stock. attribute_ids keeps
 * the pipe-separated product attribute signature as numbers so clients can request
 * /goods/{id}/price-quote instead of computing prices locally.
 */
fun Route.quotationRoutes(svc: AppServices) {
    get("/quotation") {
        val categoryId = call.queryLong("category_id")
        val brandId = call.queryLong("brand_id")
        val q = call.queryString("q")
        val paging = call.parsePaging()

        val where = StringBuilder("g.is_on_sale = 1 AND g.is_delete = 0")
        val params = ArrayList<Any?>()
        if (categoryId != null) {
            where.append(" AND g.cat_id = ?")
            params.add(categoryId)
        }
        if (brandId != null) {
            where.append(" AND g.brand_id = ?")
            params.add(brandId)
        }
        if (q != null) {
            val like = "%" + escapeLike(q) + "%"
            where.append(" AND (g.goods_name LIKE ? ESCAPE '\\' OR g.goods_sn LIKE ? ESCAPE '\\' " +
                "OR p.product_sn LIKE ? ESCAPE '\\')")
            repeat(3) { params.add(like) }
        }
        val whereSql = where.toString()
        val body = svc.db.read { c ->
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_products p JOIN ecs_goods g ON g.goods_id = p.goods_id " +
                    "WHERE $whereSql",
                params,
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT p.product_id, p.product_sn, p.goods_attr, p.product_number, " +
                        "g.goods_id, g.goods_sn, g.goods_name, g.cat_id, g.shop_price, g.market_price, " +
                        "g.promote_price, g.promote_start_date, g.promote_end_date, g.is_promote " +
                        "FROM ecs_products p JOIN ecs_goods g ON g.goods_id = p.goods_id " +
                        "WHERE $whereSql ORDER BY p.product_id LIMIT ? OFFSET ?",
                    params + listOf(paging.limit, paging.offset),
                ) { rs ->
                    val promote = rs.amount("promote_price")
                    val inWindow = rs.int("is_promote") == 1 &&
                        System.currentTimeMillis() / 1000L in rs.long("promote_start_date")..rs.long("promote_end_date")
                    val unit = if (inWindow && promote != "0.00") promote else rs.amount("shop_price")
                    val attributeIds = rs.str("goods_attr").split('|', ';')
                        .mapNotNull { it.trim().toLongOrNull() }
                    buildJsonObject {
                        put("product_id", rs.long("product_id"))
                        put("product_sn", rs.str("product_sn"))
                        put("goods_id", rs.long("goods_id"))
                        put("goods_sn", rs.str("goods_sn"))
                        put("goods_name", rs.str("goods_name"))
                        put("category_id", rs.long("cat_id"))
                        put("base_price", unit)
                        put("stock", rs.long("product_number"))
                        put("attribute_ids", JsonArray(attributeIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
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
