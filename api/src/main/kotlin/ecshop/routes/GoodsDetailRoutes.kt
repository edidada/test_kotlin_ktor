package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.exists
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.pathLong
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M1 — GET /api/v1/goods/{id} (maps PHP goods.php?id=; docs/08 first URL).
 * Detail with gallery, attributes, tag statistics and a stock summary.
 * Off-sale/deleted/missing goods are all 404 so visibility cannot leak.
 */
fun Route.goodsDetailRoutes(svc: AppServices) {
    get("/goods/{id}") {
        val goodsId = call.pathLong("id")
        val body = svc.db.read { c ->
            val goods = c.selectOne(
                "SELECT g.*, b.brand_name, cat.cat_name FROM ecs_goods g " +
                    "LEFT JOIN ecs_brand b ON b.brand_id = g.brand_id " +
                    "LEFT JOIN ecs_category cat ON cat.cat_id = g.cat_id " +
                    "WHERE g.goods_id = ? AND g.is_on_sale = 1 AND g.is_delete = 0",
                listOf(goodsId),
            ) { rs ->
                buildJsonObject {
                    put("goods_id", rs.long("goods_id"))
                    put("goods_sn", rs.str("goods_sn"))
                    put("name", rs.str("goods_name"))
                    put("cat_id", rs.long("cat_id"))
                    put("cat_name", rs.str("cat_name"))
                    put("brand_id", rs.long("brand_id"))
                    put("brand_name", rs.str("brand_name"))
                    put("shop_price", displayPriceRs(rs))
                    put("market_price", rs.amount("market_price"))
                    put("promote_price", rs.amount("promote_price"))
                    put("promote_start_date", rs.long("promote_start_date"))
                    put("promote_end_date", rs.long("promote_end_date"))
                    put("goods_brief", rs.str("goods_brief"))
                    put("goods_desc", rs.str("goods_desc"))
                    put("goods_thumb", rs.str("goods_thumb"))
                    put("goods_img", rs.str("goods_img"))
                    put("original_img", rs.str("original_img"))
                    put("keywords", rs.str("keywords"))
                    put("weight", rs.amount("goods_weight"))
                    put("is_real", rs.int("is_real") == 1)
                    put("is_best", rs.int("is_best") == 1)
                    put("is_new", rs.int("is_new") == 1)
                    put("is_hot", rs.int("is_hot") == 1)
                    put("is_promote", rs.int("is_promote") == 1)
                    put("click_count", rs.long("click_count"))
                    put("add_time", rs.long("add_time"))
                }
            } ?: throw notFound("goods not found")

            val gallery = JsonArray(
                c.select(
                    "SELECT img_id, img_url, thumb_url, img_desc FROM ecs_goods_gallery " +
                        "WHERE goods_id = ? ORDER BY img_id",
                    listOf(goodsId),
                ) { rs ->
                    buildJsonObject {
                        put("img_id", rs.long("img_id"))
                        put("img_url", rs.str("img_url"))
                        put("thumb_url", rs.str("thumb_url"))
                        put("desc", rs.str("img_desc"))
                    }
                },
            )
            val attributes = JsonArray(
                c.select(
                    "SELECT goods_attr_id, attr_id, attr_value, attr_price FROM ecs_goods_attr " +
                        "WHERE goods_id = ? ORDER BY goods_attr_id",
                    listOf(goodsId),
                ) { rs ->
                    buildJsonObject {
                        put("goods_attr_id", rs.long("goods_attr_id"))
                        put("attr_id", rs.long("attr_id"))
                        put("value", rs.str("attr_value"))
                        put("price", rs.amount("attr_price"))
                    }
                },
            )
            val tags = JsonArray(
                c.select(
                    "SELECT tag_words, COUNT(*) AS use_count FROM ecs_tag " +
                        "WHERE goods_id = ? GROUP BY tag_words ORDER BY use_count DESC, tag_words",
                    listOf(goodsId),
                ) { rs ->
                    buildJsonObject {
                        put("word", rs.str("tag_words"))
                        put("count", rs.long("use_count"))
                    }
                },
            )
            val stock = c.selectOne(
                "SELECT goods_number FROM ecs_goods WHERE goods_id = ?",
                listOf(goodsId),
            ) { it.long("goods_number") } ?: 0L
            val sellable = c.exists(
                "SELECT 1 AS found FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0 " +
                    "AND is_alone_sale = 1",
                listOf(goodsId),
            )

            buildJsonObject {
                put("goods", goods)
                put("gallery", gallery)
                put("attributes", attributes)
                put("tags", tags)
                put("stock_available", stock)
                put("is_sellable", sellable && stock > 0)
            }
        }
        call.respond(body)
    }
}
