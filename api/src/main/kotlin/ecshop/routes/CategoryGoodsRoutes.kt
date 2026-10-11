package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.listEnvelope
import ecshop.http.parsePaging
import ecshop.http.pathLong
import ecshop.http.queryLong
import ecshop.http.queryString
import ecshop.http.resolveSort
import ecshop.http.notFound
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M1 — GET /api/v1/categories/{id}/goods (maps PHP category.php?id=).
 * Paged goods of a visible category including its subtree; supports brand_id and sort.
 */
fun Route.categoryGoodsRoutes(svc: AppServices) {
    get("/categories/{id}/goods") {
        val catId = call.pathLong("id")
        val brandId = call.queryLong("brand_id")
        val sort = resolveSort(call.queryString("sort"), GOODS_SORTS, "add_time DESC")
        val paging = call.parsePaging()

        val body = svc.db.read { c ->
            val category = c.selectOne(
                "SELECT cat_id, cat_name, parent_id FROM ecs_category WHERE cat_id = ? AND is_show = 1",
                listOf(catId),
            ) { rs -> Triple(rs.long("cat_id"), rs.str("cat_name"), rs.long("parent_id")) }
                ?: throw notFound("category not found")

            // Materialize the subtree ids, then bind them as placeholders (never concatenated).
            val subtree = c.select(
                "WITH RECURSIVE cat_tree(id) AS (" +
                    "SELECT cat_id FROM ecs_category WHERE cat_id = ? UNION ALL " +
                    "SELECT ch.cat_id FROM ecs_category ch JOIN cat_tree t ON ch.parent_id = t.id" +
                    ") SELECT id FROM cat_tree",
                listOf(catId),
            ) { it.long("id") }

            val where = StringBuilder("cat_id IN (${subtree.joinToString(",") { "?" }}) AND $GOODS_VISIBLE")
            val params = ArrayList<Any?>(subtree)
            if (brandId != null) {
                where.append(" AND brand_id = ?")
                params.add(brandId)
            }
            val whereSql = where.toString()
            val total = c.selectOne("SELECT COUNT(*) AS total FROM ecs_goods WHERE $whereSql", params) {
                it.long("total")
            } ?: 0L
            val items = JsonArray(
                c.select(
                    "$GOODS_LIST_SELECT FROM ecs_goods WHERE $whereSql ORDER BY $sort, goods_id LIMIT ? OFFSET ?",
                    params + listOf(paging.limit, paging.offset),
                ) { rs -> goodsListJson(rs) },
            )
            buildJsonObject {
                put("category_id", category.first)
                put("category_name", category.second)
                put("parent_id", category.third)
                put("page", paging.page)
                put("page_size", paging.pageSize)
                put("total", total)
                put("items", items)
            }
        }
        call.respond(body)
    }
}

/** Column list shared by every public goods projection. */
const val GOODS_LIST_SELECT = """
    SELECT goods_id, goods_sn, cat_id, goods_name, brand_id, shop_price, market_price,
           promote_price, promote_start_date, promote_end_date, goods_number, goods_thumb,
           is_best, is_new, is_hot, is_promote, add_time
"""
