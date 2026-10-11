package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.cents
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.Money
import ecshop.http.notFound
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/**
 * M2 — GET /api/v1/checkout/options and POST /api/v1/checkout/quote
 * (maps PHP flow.php?step=checkout selection stage).
 * The quote trusts only server-side data: it re-reads the cart, current prices, the enabled
 * shipping/payment rows and their fixed fees, and computes everything in integer cents.
 * The eventual order creation re-computes from scratch; this response is display-only.
 */
@Serializable
data class CheckoutQuoteRequest(val address_id: Long = 0, val shipping_id: Long = 0, val payment_id: Long = 0)

fun Route.checkoutRoutes(svc: AppServices) {
    get("/checkout/options") {
        val uid = requireUserId(svc.db, call)
        val body = svc.db.read { c ->
            val shippings = JsonArray(
                c.select("SELECT shipping_id, shipping_name, shipping_fee FROM ecs_shipping WHERE enabled = 1 ORDER BY shipping_id", emptyList()) { rs ->
                    buildJsonObject {
                        put("shipping_id", rs.long("shipping_id"))
                        put("name", rs.str("shipping_name"))
                        put("fee", rs.amount("shipping_fee"))
                    }
                },
            )
            val payments = JsonArray(
                c.select("SELECT pay_id, pay_name, pay_fee FROM ecs_payment WHERE enabled = 1 ORDER BY pay_id", emptyList()) { rs ->
                    buildJsonObject {
                        put("payment_id", rs.long("pay_id"))
                        put("name", rs.str("pay_name"))
                        put("fee", rs.amount("pay_fee"))
                    }
                },
            )
            val addresses = JsonArray(
                c.select(
                    "SELECT address_id, consignee, address, mobile, is_default FROM ecs_user_address WHERE user_id = ? ORDER BY address_id",
                    listOf(uid),
                ) { rs ->
                    buildJsonObject {
                        put("address_id", rs.long("address_id"))
                        put("consignee", rs.str("consignee"))
                        put("address", rs.str("address"))
                        put("mobile", rs.str("mobile"))
                        put("is_default", rs.long("is_default") == 1L)
                    }
                },
            )
            buildJsonObject {
                put("shippings", shippings)
                put("payments", payments)
                put("addresses", addresses)
            }
        }
        call.respond(body)
    }

    post("/checkout/quote") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<CheckoutQuoteRequest>()
        if (req.address_id <= 0 || req.shipping_id <= 0 || req.payment_id <= 0) {
            throw validationError("address_id, shipping_id and payment_id are all required", "address_id")
        }
        val quote = svc.db.read { c ->
            c.selectOne("SELECT address_id FROM ecs_user_address WHERE address_id = ? AND user_id = ?", listOf(req.address_id, uid)) { it.long("address_id") }
                ?: throw notFound("address not found")
            val shipping = c.selectOne("SELECT shipping_fee FROM ecs_shipping WHERE shipping_id = ? AND enabled = 1", listOf(req.shipping_id)) { rs -> rs.cents("shipping_fee") }
                ?: throw notFound("shipping method not found")
            val payment = c.selectOne("SELECT pay_fee FROM ecs_payment WHERE pay_id = ? AND enabled = 1", listOf(req.payment_id)) { rs -> rs.cents("pay_fee") }
                ?: throw notFound("payment method not found")
            val lines = c.select(
                CART_COLS + CART_FROM + "WHERE c.user_id = ? ORDER BY c.rec_id",
                listOf(uid),
            ) { rs -> quoteLine(rs) }
            if (lines.isEmpty()) throw ecshop.http.invalidState("cart is empty or contains no sellable goods")
            val goodsAmount = lines.sumOf { it.second }
            val orderAmount = goodsAmount + shipping + payment
            buildJsonObject {
                put("address_id", req.address_id)
                put("shipping_id", req.shipping_id)
                put("payment_id", req.payment_id)
                put("items", JsonArray(lines.map { it.first }))
                put("goods_amount", Money.fromCents(goodsAmount))
                put("shipping_fee", Money.fromCents(shipping))
                put("payment_fee", Money.fromCents(payment))
                put("order_amount", Money.fromCents(orderAmount))
            }
        }
        call.respond(quote)
    }
}

/** A cart line rendered as JSON plus its line total in cents (unit price x quantity). */
private fun quoteLine(rs: ResultSet): Pair<JsonObject, Long> {
    val price = displayPriceRs(rs)
    val unitCents = Money.parseCents(price) ?: 0L
    val qty = rs.long("goods_number")
    val line = buildJsonObject {
        put("goods_id", rs.long("goods_id"))
        put("name", rs.str("goods_name"))
        put("price", price)
        put("quantity", qty)
        put("line_total", Money.fromCents(unitCents * qty))
    }
    return line to unitCents * qty
}
