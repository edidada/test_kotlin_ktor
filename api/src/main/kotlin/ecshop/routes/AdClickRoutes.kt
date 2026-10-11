package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — POST /api/v1/ads/{id}/click (maps PHP affiche.php click accounting).
 * Only enabled ads inside their live window accept clicks. One transaction bumps
 * ad.click_count and upserts the per-referer counter into adsense, then returns the
 * redirect_url for the front end to navigate itself — the dangerous JS echo of the old
 * affiche.php?act=js is deliberately not reproduced.
 */
@Serializable
data class AdClickRequest(val referer: String = "")

fun Route.adClickRoutes(svc: AppServices) {
    post("/ads/{id}/click") {
        val adId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("ad not found")
        val req = call.receive<AdClickRequest>()
        val referer = req.referer.trim().take(255)
        val now = System.currentTimeMillis() / 1000L
        val redirect = svc.db.transaction { c ->
            val ad = c.selectOne(
                "SELECT ad_link FROM ecs_ad WHERE ad_id = ? AND enabled = 1 AND start_time <= ? AND (end_time = 0 OR end_time >= ?)",
                listOf(adId, now, now),
            ) { rs -> rs.str("ad_link") } ?: throw notFound("ad not found")
            c.execute("UPDATE ecs_ad SET click_count = click_count + 1 WHERE ad_id = ?", listOf(adId))
            c.execute(
                "INSERT INTO ecs_adsense(from_ad, referer, clicks) VALUES(?, ?, 1) ON CONFLICT(from_ad, referer) DO UPDATE SET clicks = clicks + 1",
                listOf(adId, referer),
            )
            ad
        }
        call.respond(buildJsonObject { put("redirect_url", redirect) })
    }
}
