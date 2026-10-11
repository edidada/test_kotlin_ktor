package ecshop.routes

import ecshop.AppServices
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.str
import ecshop.http.queryLong
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M1 — GET /api/v1/regions?parent=&type= (maps PHP region.php cascading selects).
 * Children of a parent region; optional region_type (1 country..4 district) narrows the level.
 */
fun Route.regionRoutes(svc: AppServices) {
    get("/regions") {
        val parent = call.queryLong("parent") ?: 0L
        val type = call.queryLong("type")
        val items = svc.db.read { c ->
            JsonArray(
                c.select(
                    "SELECT region_id, parent_id, region_name, region_type FROM ecs_region " +
                        "WHERE parent_id = ?" + (if (type != null) " AND region_type = ?" else "") +
                        " ORDER BY region_id",
                    if (type != null) listOf(parent, type) else listOf(parent),
                ) { rs ->
                    buildJsonObject {
                        put("region_id", rs.long("region_id"))
                        put("parent_id", rs.long("parent_id"))
                        put("name", rs.str("region_name"))
                        put("region_type", rs.int("region_type"))
                    }
                },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }
}
