package ecshop.routes

import ecshop.AppServices
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M0 — GET /healthz (docs/02_url_api_list.md). Verifies the process is up and SQLite reachable.
 */
fun Route.healthRoutes(svc: AppServices) {
    get("/healthz") {
        val ok = svc.db.read { c ->
            c.prepareStatement("SELECT 1").use { it.executeQuery().next() }
        }
        call.respond(
            buildJsonObject {
                put("status", if (ok) "ok" else "degraded")
            },
        )
    }
}
