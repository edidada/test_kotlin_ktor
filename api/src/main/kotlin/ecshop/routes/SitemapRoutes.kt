package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * M4 — GET /sitemap.xml (maps PHP sitemaps.php). Indexes the home page, visible categories,
 * up to 300 public goods, article categories and public articles. Absolute URLs come from
 * site.base_url config; the request Host header is never trusted.
 */
fun Route.sitemapRoutes(svc: AppServices) {
    get("/sitemap.xml") {
        val base = svc.config.baseUrl
        val xml = svc.db.read { c ->
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n")
            appendUrl(sb, "$base/")
            c.select(
                "SELECT cat_id FROM ecs_category WHERE is_show = 1 ORDER BY cat_id",
            ) { rs -> appendUrl(sb, "$base/category/${rs.long("cat_id")}") }
            c.select(
                "SELECT goods_id, last_update FROM ecs_goods WHERE is_on_sale = 1 AND is_delete = 0 " +
                    "ORDER BY goods_id LIMIT 300",
            ) { rs -> appendUrl(sb, "$base/goods/${rs.long("goods_id")}") }
            c.select(
                "SELECT cat_id FROM ecs_article_cat WHERE is_show = 1 ORDER BY cat_id",
            ) { rs -> appendUrl(sb, "$base/article_cat/${rs.long("cat_id")}") }
            c.select(
                "SELECT article_id FROM ecs_article WHERE is_open = 1 ORDER BY article_id",
            ) { rs -> appendUrl(sb, "$base/article/${rs.long("article_id")}") }
            sb.append("</urlset>\n")
            sb.toString()
        }
        call.respondText(xml, ContentType.Application.Xml)
    }
}

private fun appendUrl(sb: StringBuilder, loc: String) {
    sb.append("  <url><loc>").append(escapeXml(loc)).append("</loc></url>\n")
}

internal fun escapeXml(value: String): String = buildString(value.length) {
    for (ch in value) when (ch) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        '"' -> append("&quot;")
        '\'' -> append("&apos;")
        else -> append(ch)
    }
}
