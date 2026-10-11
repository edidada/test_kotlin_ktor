package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.parsePaging
import ecshop.http.pathLong
import ecshop.http.queryString
import ecshop.http.resolveSort
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared brand projection (list variants carry goods_count). */
fun brandJson(rs: java.sql.ResultSet) = buildJsonObject {
    put("brand_id", rs.long("brand_id"))
    put("name", rs.str("brand_name"))
    put("logo", rs.str("brand_logo"))
    put("goods_count", rs.long("goods_count"))
}

/**
 * M1 — GET /api/v1/brands (maps PHP brand.php): visible brands with sellable goods counts.
 */
fun Route.brandListRoutes(svc: AppServices) {
    get("/brands") {
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val total = c.selectOne("SELECT COUNT(*) AS total FROM ecs_brand WHERE is_show = 1") {
                it.long("total")
            } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT brand_id, brand_name, brand_logo, " +
                        "(SELECT COUNT(*) FROM ecs_goods g WHERE g.brand_id = ecs_brand.brand_id " +
                        " AND g.is_on_sale = 1 AND g.is_delete = 0) AS goods_count " +
                        "FROM ecs_brand WHERE is_show = 1 ORDER BY sort_order, brand_id LIMIT ? OFFSET ?",
                    listOf(paging.limit, paging.offset),
                ) { rs -> brandJson(rs) },
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

/**
 * M1 — GET /api/v1/brands/{id}/goods (brand goods page).
 */
fun Route.brandGoodsRoutes(svc: AppServices) {
    get("/brands/{id}/goods") {
        val brandId = call.pathLong("id")
        val sort = resolveSort(call.queryString("sort"), GOODS_SORTS, "add_time DESC")
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val brandName = c.selectOne(
                "SELECT brand_name FROM ecs_brand WHERE brand_id = ? AND is_show = 1",
                listOf(brandId),
            ) { it.str("brand_name") } ?: throw notFound("brand not found")
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_goods WHERE brand_id = ? AND $GOODS_VISIBLE",
                listOf(brandId),
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "$GOODS_LIST_SELECT FROM ecs_goods WHERE brand_id = ? AND $GOODS_VISIBLE " +
                        "ORDER BY $sort, goods_id LIMIT ? OFFSET ?",
                    listOf(brandId, paging.limit, paging.offset),
                ) { rs -> goodsListJson(rs) },
            )
            buildJsonObject {
                put("brand_id", brandId)
                put("brand_name", brandName)
                put("page", paging.page)
                put("page_size", paging.pageSize)
                put("total", total)
                put("items", items)
            }
        }
        call.respond(body)
    }
}
