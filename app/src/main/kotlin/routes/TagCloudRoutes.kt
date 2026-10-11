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
 * M3 — GET /api/v1/tags (maps PHP tag_cloud.php).
 * Public tag cloud over still-sellable goods; count desc, word asc.
 */
fun Route.tagCloudRoutes(svc: AppServices) {
    get("/tags") {
        val body = svc.db.read { c ->
            val items = JsonArray(
                c.select(
                    "SELECT t.tag_words AS word, COUNT(*) AS count FROM ecs_tag t " +
                        "JOIN ecs_goods g ON g.goods_id = t.goods_id " +
                        "WHERE g.is_on_sale = 1 AND g.is_delete = 0 " +
                        "GROUP BY t.tag_words ORDER BY count DESC, word ASC",
                ) { rs ->
                    buildJsonObject {
                        put("word", rs.str("word"))
                        put("count", rs.long("count"))
                    }
                },
            )
            buildJsonObject { put("items", items) }
        }
        call.respond(body)
    }
}
