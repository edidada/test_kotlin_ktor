package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.str
import ecshop.http.queryString
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * M4 — GET /feed.xml (maps PHP feed.php). RSS 2.0 over the newest public goods by default;
 * cat (with subtree) / brand narrow the goods feed, and type switches to group_buy, snatch,
 * auction, exchange, activity, package or article_cat{id}. Hard cap of 100 items; link root
 * comes from site.base_url.
 */
fun Route.feedRoutes(svc: AppServices) {
    get("/feed.xml") {
        val base = svc.config.baseUrl
        val cat = call.queryString("cat")?.toLongOrNull()
        val brand = call.queryString("brand")?.toLongOrNull()
        val type = call.queryString("type") ?: ""
        val now = System.currentTimeMillis() / 1000L

        val items = mutableListOf<FeedItem>()
        svc.db.read { c ->
            when {
                type == "" -> {
                    val where = StringBuilder("g.is_on_sale = 1 AND g.is_delete = 0")
                    val params = ArrayList<Any?>()
                    if (cat != null) {
                        val subtree = c.select(
                            "WITH RECURSIVE cat_tree(id) AS (SELECT cat_id FROM ecs_category WHERE cat_id = ? " +
                                "UNION ALL " +
                                "SELECT ch.cat_id FROM ecs_category ch JOIN cat_tree t ON ch.parent_id = t.id) " +
                                "SELECT id FROM cat_tree",
                            listOf(cat),
                        ) { it.long("id") }
                        where.append(" AND g.cat_id IN (").append(subtree.joinToString(",") { "?" }).append(")")
                        params.addAll(subtree)
                    }
                    if (brand != null) {
                        where.append(" AND g.brand_id = ?")
                        params.add(brand)
                    }
                    c.select(
                        "SELECT g.goods_id, g.goods_name, g.goods_brief FROM ecs_goods g " +
                            "WHERE $where ORDER BY g.add_time DESC, g.goods_id DESC LIMIT 100",
                        params,
                    ) { rs ->
                        items.add(
                            FeedItem(
                                rs.str("goods_name"),
                                "$base/goods/${rs.long("goods_id")}",
                                rs.str("goods_brief"),
                                now,
                            ),
                        )
                    }
                }
                type in setOf("group_buy", "snatch", "auction", "package") -> {
                    val actType = when (type) {
                        "snatch" -> 0
                        "group_buy" -> 1
                        "auction" -> 2
                        else -> 4
                    }
                    val pathPrefix = if (type == "package") "package" else type
                    c.select(
                        "SELECT a.act_id, a.act_name, a.act_desc FROM ecs_goods_activity a " +
                            "JOIN ecs_goods g ON g.goods_id = a.goods_id " +
                            "WHERE a.act_type = ? AND a.is_finished = 0 AND a.start_time <= ? AND a.end_time >= ? " +
                            "AND g.is_on_sale = 1 AND g.is_delete = 0 " +
                            "ORDER BY a.act_id DESC LIMIT 100",
                        listOf(actType, now, now),
                    ) { rs ->
                        items.add(
                            FeedItem(
                                rs.str("act_name"),
                                "$base/$pathPrefix/${rs.long("act_id")}",
                                rs.str("act_desc"),
                                now,
                            ),
                        )
                    }
                }
                type == "exchange" -> c.select(
                    "SELECT g.goods_id, g.goods_name, g.goods_brief FROM ecs_exchange_goods eg " +
                        "JOIN ecs_goods g ON g.goods_id = eg.goods_id " +
                        "WHERE eg.is_exchange = 1 AND g.is_on_sale = 1 AND g.is_delete = 0 " +
                        "ORDER BY eg.goods_id DESC LIMIT 100",
                ) { rs ->
                    items.add(
                        FeedItem(
                            rs.str("goods_name"),
                            "$base/exchange/${rs.long("goods_id")}",
                            rs.str("goods_brief"),
                            now,
                        ),
                    )
                }
                type == "activity" -> c.select(
                    "SELECT act_id, act_name FROM ecs_favourable_activity " +
                        "WHERE start_time <= ? AND end_time >= ? ORDER BY sort_order, act_id LIMIT 100",
                    listOf(now, now),
                ) { rs ->
                    items.add(
                        FeedItem(
                            rs.str("act_name"),
                            "$base/activity/${rs.long("act_id")}",
                            "",
                            now,
                        ),
                    )
                }
                type.startsWith("article_cat") -> {
                    val catId = type.removePrefix("article_cat").toLongOrNull() ?: 0L
                    c.select(
                        "SELECT article_id, title, article_desc FROM ecs_article " +
                            "WHERE cat_id = ? AND is_open = 1 ORDER BY article_id DESC LIMIT 100",
                        listOf(catId),
                    ) { rs ->
                        items.add(
                            FeedItem(
                                rs.str("title"),
                                "$base/article/${rs.long("article_id")}",
                                rs.str("article_desc"),
                                now,
                            ),
                        )
                    }
                }
                else -> {
                    throw ecshop.http.validationError(
                        "type must be one of group_buy,snatch,auction,exchange,activity,package,article_catN",
                        "type",
                    )
                }
            }
        }

        val pub = DateTimeFormatter.RFC_1123_DATE_TIME.format(
            Instant.ofEpochSecond(now).atZone(ZoneOffset.UTC),
        )
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<rss version=\"2.0\"><channel>\n")
            append("<title>ECShop Kotlin Feed</title>\n")
            append("<link>").append(escapeXml("$base/")).append("</link>\n")
            append("<description>Latest public catalog updates</description>\n")
            append("<pubDate>").append(pub).append("</pubDate>\n")
            for (item in items) {
                append("<item>")
                append("<title>").append(escapeXml(item.title)).append("</title>")
                append("<link>").append(escapeXml(item.link)).append("</link>")
                append("<description>").append(escapeXml(item.description)).append("</description>")
                append("<pubDate>").append(pub).append("</pubDate>")
                append("</item>\n")
            }
            append("</channel></rss>\n")
        }
        call.respondText(xml, ContentType.Application.Xml)
    }
}

private data class FeedItem(val title: String, val link: String, val description: String, val time: Long)
