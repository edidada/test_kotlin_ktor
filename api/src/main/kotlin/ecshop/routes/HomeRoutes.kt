package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
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
 * M1 — GET /api/v1/home (docs/02_url_api_list.md, maps PHP index.php).
 * Aggregates the shop front page: category nav plus the four ECSHOP recommendation columns,
 * new articles and image ads. Each column is capped at 10 visible goods like the PHP index.
 */
fun Route.homeRoutes(svc: AppServices) {
    get("/home") {
        val body = svc.db.read { c ->
            val categories = JsonArray(
                c.select(
                    "SELECT cat_id, cat_name, parent_id, sort_order FROM ecs_category " +
                        "WHERE is_show = 1 ORDER BY parent_id, sort_order, cat_id",
                ) { rs ->
                    buildJsonObject {
                        put("cat_id", rs.long("cat_id"))
                        put("cat_name", rs.str("cat_name"))
                        put("parent_id", rs.long("parent_id"))
                    }
                },
            )
            val articles = JsonArray(
                c.select(
                    "SELECT article_id, cat_id, title, author, article_desc, keywords " +
                        "FROM ecs_article WHERE is_open = 1 ORDER BY article_id DESC LIMIT 10",
                ) { rs ->
                    buildJsonObject {
                        put("article_id", rs.long("article_id"))
                        put("cat_id", rs.long("cat_id"))
                        put("title", rs.str("title"))
                        put("author", rs.str("author"))
                        put("intro", rs.str("article_desc"))
                        put("keywords", rs.str("keywords"))
                    }
                },
            )
            val ads = JsonArray(
                c.select(
                    "SELECT ad_id, position_id, media_type, ad_name, ad_link, ad_code, start_time, end_time " +
                        "FROM ecs_ad WHERE enabled = 1 AND ? BETWEEN start_time AND end_time " +
                        "ORDER BY ad_id",
                    listOf(System.currentTimeMillis() / 1000L),
                ) { rs ->
                    buildJsonObject {
                        put("ad_id", rs.long("ad_id"))
                        put("position_id", rs.long("position_id"))
                        put("media_type", rs.int("media_type"))
                        put("ad_name", rs.str("ad_name"))
                        put("ad_link", rs.str("ad_link"))
                        put("ad_code", rs.str("ad_code"))
                    }
                },
            )

            fun recommended(flag: String) = JsonArray(
                c.select(
                    "SELECT goods_id, goods_sn, cat_id, goods_name, brand_id, shop_price, market_price, " +
                        "promote_price, promote_start_date, promote_end_date, goods_number, goods_thumb, " +
                        "is_best, is_new, is_hot, is_promote, add_time " +
                        "FROM ecs_goods WHERE is_on_sale = 1 AND is_delete = 0 AND $flag = 1 " +
                        "ORDER BY sort_order, goods_id LIMIT 10",
                ) { rs -> goodsListJson(rs) },
            )

            buildJsonObject {
                put("categories", categories)
                put("best_goods", recommended("is_best"))
                put("new_goods", recommended("is_new"))
                put("hot_goods", recommended("is_hot"))
                put("promote_goods", recommended("is_promote"))
                put("articles", articles)
                put("ads", ads)
            }
        }
        call.respond(body)
    }
}
