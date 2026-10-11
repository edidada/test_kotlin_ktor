package ecshop.routes

import ecshop.AppServices
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
 * M3 — GET /api/v1/goods/{id}/gallery (maps PHP gallery.php?id=).
 * 404 when the goods is invisible/deleted or has no gallery images at all.
 */
fun Route.goodsGalleryRoutes(svc: AppServices) {
    get("/goods/{id}/gallery") {
        val goodsId = call.pathLong("id")
        val body = svc.db.read { c ->
            val name = c.selectOne(
                "SELECT goods_name FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                listOf(goodsId),
            ) { it.str("goods_name") } ?: throw notFound("goods not found")
            val images = c.select(
                "SELECT img_id, thumb_url, img_url, img_original, img_desc FROM ecs_goods_gallery " +
                    "WHERE goods_id = ? ORDER BY img_id",
                listOf(goodsId),
            ) { rs ->
                buildJsonObject {
                    put("img_id", rs.long("img_id"))
                    put("thumb_url", rs.str("thumb_url"))
                    put("img_url", rs.str("img_url"))
                    put("img_original", rs.str("img_original"))
                    put("desc", rs.str("img_desc"))
                }
            }
            if (images.isEmpty()) throw notFound("gallery not found")
            buildJsonObject {
                put("goods_id", goodsId)
                put("goods_name", name)
                put("images", JsonArray(images))
            }
        }
        call.respond(body)
    }
}
