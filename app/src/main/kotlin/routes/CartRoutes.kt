package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.conflict
import ecshop.http.notFound
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * M2 — GET/POST /api/v1/me/cart, PATCH/DELETE /api/v1/me/cart/{recId}
 * (maps PHP flow.php?step=cart / add_to_cart / update_cart / drop_goods).
 * Cart rows carry an optimistic-lock version: PATCH matches rec_id + user_id + version in a
 * single UPDATE, so a stale client never silently overwrites newer quantities (409 on miss).
 * POST for the same user+goods accumulates atomically via the UNIQUE(user_id, goods_id) upsert.
 */
@Serializable
data class AddCartRequest(val goods_id: Long = 0, val quantity: Int = 1)

@Serializable
data class UpdateCartRequest(val quantity: Int = 0, val version: Long = 0)

private const val CART_FROM =
    "FROM ecs_cart c JOIN ecs_goods g ON g.goods_id = c.goods_id AND g.is_on_sale = 1 AND g.is_delete = 0 "

private const val CART_COLS =
    "SELECT c.rec_id, c.goods_id, c.goods_number, c.version, g.goods_name, g.shop_price, g.promote_price, " +
        "g.promote_start_date, g.promote_end_date, g.is_promote, g.goods_number AS stock "

fun Route.cartRoutes(svc: AppServices) {
    get("/me/cart") {
        val uid = requireUserId(svc.db, call)
        val body = svc.db.read { c ->
            var totalQty = 0L
            val items = JsonArray(
                c.select(
                    CART_COLS + CART_FROM + "WHERE c.user_id = ? ORDER BY c.rec_id",
                    listOf(uid),
                ) { rs ->
                    totalQty += rs.long("goods_number")
                    cartLineJson(rs)
                },
            )
            buildJsonObject {
                put("items", items)
                put("total_quantity", totalQty)
            }
        }
        call.respond(body)
    }

    post("/me/cart") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<AddCartRequest>()
        if (req.goods_id <= 0) throw validationError("goods_id must be positive", "goods_id")
        if (req.quantity !in 1..9999) throw validationError("quantity must be between 1 and 9999", "quantity")
        svc.db.transaction { c ->
            val goodsId = c.selectOne(
                "SELECT goods_id FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                listOf(req.goods_id),
            ) { it.long("goods_id") } ?: throw notFound("goods not found")
            c.execute(
                "INSERT INTO ecs_cart(user_id, goods_id, goods_number, version) VALUES(?, ?, ?, 1) " +
                    "ON CONFLICT(user_id, goods_id) DO UPDATE SET goods_number = goods_number + excluded.goods_number, version = version + 1",
                listOf(uid, goodsId, req.quantity),
            )
        }
        val line = svc.db.read { c ->
            c.selectOne(
                CART_COLS + CART_FROM + "WHERE c.user_id = ? AND c.goods_id = ? ORDER BY c.rec_id LIMIT 1",
                listOf(uid, req.goods_id),
            ) { cartLineJson(it) }
        } ?: throw notFound("goods not found")
        call.respond(HttpStatusCode.Created, line)
    }

    patch("/me/cart/{id}") {
        val uid = requireUserId(svc.db, call)
        val recId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("cart line not found")
        val req = call.receive<UpdateCartRequest>()
        if (req.quantity !in 1..9999) throw validationError("quantity must be between 1 and 9999", "quantity")
        val updated = svc.db.transaction { c ->
            c.execute(
                "UPDATE ecs_cart SET goods_number = ?, version = version + 1 WHERE rec_id = ? AND user_id = ? AND version = ?",
                listOf(req.quantity, recId, uid, req.version),
            )
        }
        if (updated != 1) throw conflict("cart line changed concurrently, re-fetch GET /me/cart")
        val line = svc.db.read { c ->
            c.selectOne(
                CART_COLS + CART_FROM + "WHERE c.rec_id = ? AND c.user_id = ?",
                listOf(recId, uid),
            ) { cartLineJson(it) }
        } ?: throw notFound("cart line not found")
        call.respond(line)
    }

    delete("/me/cart/{id}") {
        val uid = requireUserId(svc.db, call)
        val recId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("cart line not found")
        val deleted = svc.db.transaction { c ->
            c.execute("DELETE FROM ecs_cart WHERE rec_id = ? AND user_id = ?", listOf(recId, uid))
        }
        if (deleted != 1) throw notFound("cart line not found")
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun cartLineJson(rs: ResultSet) = buildJsonObject {
    put("id", rs.long("rec_id"))
    put("goods_id", rs.long("goods_id"))
    put("name", rs.str("goods_name"))
    put("price", displayPriceRs(rs))
    put("quantity", rs.long("goods_number"))
    put("stock", rs.long("stock"))
    put("version", rs.long("version"))
}
