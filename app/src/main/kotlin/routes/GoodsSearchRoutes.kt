package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.http.escapeLike
import ecshop.http.listEnvelope
import ecshop.http.parsePaging
import ecshop.http.queryLong
import ecshop.http.queryString
import ecshop.http.resolveSort
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M1 — GET /api/v1/goods (maps PHP search.php?keywords= and the generic goods list).
 * Supports q (LIKE on name/sn/brief/keywords), category_id, brand_id, sort and paging.
 */
fun Route.goodsSearchRoutes(svc: AppServices) {
    get("/goods") {
        val q = call.queryString("q")
        val categoryId = call.queryLong("category_id")
        val brandId = call.queryLong("brand_id")
        val sort = resolveSort(call.queryString("sort"), GOODS_SORTS, "newest_default")
        val paging = call.parsePaging()

        val where = StringBuilder(GOODS_VISIBLE)
        val params = ArrayList<Any?>()
        if (categoryId != null) {
            where.append(" AND cat_id = ?")
            params.add(categoryId)
        }
        if (brandId != null) {
            where.append(" AND brand_id = ?")
            params.add(brandId)
        }
        if (q != null) {
            val like = "%" + escapeLike(q) + "%"
            where.append(" AND (goods_name LIKE ? ESCAPE '\\' OR goods_sn LIKE ? ESCAPE '\\' " +
                "OR goods_brief LIKE ? ESCAPE '\\' OR keywords LIKE ? ESCAPE '\\')")
            repeat(4) { params.add(like) }
        }
        val whereSql = where.toString()
        val orderSql = if (sort == "newest_default") "add_time DESC, goods_id DESC" else sort

        val body = svc.db.read { c ->
            val total = c.selectOne("SELECT COUNT(*) AS total FROM ecs_goods WHERE $whereSql", params) {
                it.long("total")
            } ?: 0L
            val items = JsonArray(
                c.select(
                    "$GOODS_LIST_SELECT FROM ecs_goods WHERE $whereSql ORDER BY $orderSql LIMIT ? OFFSET ?",
                    params + listOf(paging.limit, paging.offset),
                ) { rs -> goodsListJson(rs) },
            )
            buildJsonObject {
                put("page", paging.page)
                put("page_size", paging.pageSize)
                put("total", total)
                put("items", items)
                put("q", q ?: "")
            }
        }
        call.respond(body)
    }
}
