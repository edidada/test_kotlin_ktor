package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.str
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * M4 — GET /cycle-image.xml (maps PHP cycle_image.php).
 * Current enabled image ads (media_type=0) rendered from the database instead of PHP's
 * local cache file, ordered by ad position and id.
 */
fun Route.cycleImageRoutes(svc: AppServices) {
    get("/cycle-image.xml") {
        val now = System.currentTimeMillis() / 1000L
        val xml = svc.db.read { c ->
            val rows = c.select(
                "SELECT ad_link, ad_code FROM ecs_ad WHERE enabled = 1 AND media_type = 0 " +
                    "AND start_time <= ? AND end_time >= ? ORDER BY position_id, ad_id",
                listOf(now, now),
            ) { rs -> rs.str("ad_link") to rs.str("ad_code") }
            buildString {
                append("<bcaster item_amount=\"").append(rows.size).append("\">\n")
                for ((link, img) in rows) {
                    append("<item item_url=\"").append(escapeXml(link))
                    append("\" img=\"").append(escapeXml(img)).append("\"/>\n")
                }
                append("</bcaster>\n")
            }
        }
        call.respondText(xml, ContentType.Application.Xml)
    }
}
