package ecshop.routes

import ecshop.AppServices
import ecshop.db.int
import ecshop.db.long
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.pathLong
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/ads/{id} (maps PHP affiche.php?ad_id= display). Enabled ads inside their
 * time window only; raw ad_code is presented per media_type by the client, never executed.
 */
fun Route.adDetailRoutes(svc: AppServices) {
    get("/ads/{id}") {
        val adId = call.pathLong("id")
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            c.selectOne(
                "SELECT ad_id, position_id, media_type, ad_name, ad_link, ad_code, start_time, end_time, " +
                    "click_count FROM ecs_ad WHERE ad_id = ? AND enabled = 1 AND start_time <= ? AND end_time >= ?",
                listOf(adId, now, now),
            ) { rs ->
                buildJsonObject {
                    put("ad_id", rs.long("ad_id"))
                    put("position_id", rs.long("position_id"))
                    put("media_type", rs.int("media_type"))
                    put("ad_name", rs.str("ad_name"))
                    put("ad_link", rs.str("ad_link"))
                    put("ad_code", rs.str("ad_code"))
                    put("start_time", rs.long("start_time"))
                    put("end_time", rs.long("end_time"))
                    put("click_count", rs.long("click_count"))
                }
            } ?: throw notFound("ad not found")
        }
        call.respond(body)
    }
}
