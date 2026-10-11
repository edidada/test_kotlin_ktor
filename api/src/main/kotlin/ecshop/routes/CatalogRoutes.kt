package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.str
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/catalog (maps PHP catalog.php, docs/03 "商品").
 * All visible categories and brands; goods_count only tallies on-sale, undeleted,
 * stand-alone-sellable goods. Clients build the tree from parent_id.
 */
fun Route.catalogRoutes(svc: AppServices) {
    get("/catalog") {
        val body = svc.db.read { c ->
            val categories = JsonArray(
                c.select(
                    "SELECT cat_id, cat_name, parent_id, sort_order, " +
                        "(SELECT COUNT(*) FROM ecs_goods g WHERE g.cat_id = ecs_category.cat_id " +
                        " AND g.is_on_sale = 1 AND g.is_delete = 0 AND g.is_alone_sale = 1) AS goods_count " +
                        "FROM ecs_category WHERE is_show = 1 " +
                        "ORDER BY parent_id, sort_order, cat_id",
                ) { rs ->
                    buildJsonObject {
                        put("cat_id", rs.long("cat_id"))
                        put("cat_name", rs.str("cat_name"))
                        put("parent_id", rs.long("parent_id"))
                        put("sort_order", rs.long("sort_order"))
                        put("goods_count", rs.long("goods_count"))
                    }
                },
            )
            val brands = JsonArray(
                c.select(
                    "SELECT brand_id, brand_name, brand_logo, sort_order " +
                        "FROM ecs_brand WHERE is_show = 1 ORDER BY sort_order, brand_id",
                ) { rs ->
                    buildJsonObject {
                        put("brand_id", rs.long("brand_id"))
                        put("brand_name", rs.str("brand_name"))
                        put("logo", rs.str("brand_logo"))
                    }
                },
            )
            buildJsonObject {
                put("categories", categories)
                put("brands", brands)
            }
        }
        call.respond(body)
    }
}
