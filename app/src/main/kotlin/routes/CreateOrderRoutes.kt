package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.cents
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.Money
import ecshop.http.conflict
import ecshop.http.invalidState
import ecshop.http.notFound
import ecshop.http.outOfStock
import ecshop.http.requireUserId
import ecshop.http.sha256Hex
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset

/**
 * M2 — POST /api/v1/orders (maps PHP flow.php?step=done).
 * Idempotency-Key is mandatory: the request fingerprint (sha-256 over the canonical body) is
 * stored with the order, so replaying the same user+key+body returns the original order with
 * replayed=true, while the same key with different content is a 409. Everything happens in
 * one transaction: re-read cart, conditional stock decrement per line (any shortfall rolls
 * the whole thing back with 409 out_of_stock), insert order + goods snapshots, audit row,
 * then clear the cart. Client amounts are never trusted.
 */
@Serializable
data class CreateOrderRequest(
    val address_id: Long = 0,
    val shipping_id: Long = 0,
    val payment_id: Long = 0,
    val remark: String = "",
)

fun Route.createOrderRoutes(svc: AppServices) {
    post("/orders") {
        val uid = requireUserId(svc.db, call)
        val key = call.request.headers["Idempotency-Key"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 100 }
            ?: throw validationError("Idempotency-Key header is required (1-100 chars)", "Idempotency-Key")
        val req = call.receive<CreateOrderRequest>()
        if (req.address_id <= 0 || req.shipping_id <= 0 || req.payment_id <= 0) {
            throw validationError("address_id, shipping_id and payment_id are all required", "address_id")
        }
        val remark = req.remark.trim().take(255)
        val fingerprint = sha256Hex("a=${req.address_id}|s=${req.shipping_id}|p=${req.payment_id}|r=$remark")

        val existing = svc.db.read { c ->
            c.selectOne(
                "SELECT order_id, request_fingerprint FROM ecs_order_info WHERE user_id = ? AND idempotency_key = ?",
                listOf(uid, key),
            ) { rs -> rs.long("order_id") to rs.str("request_fingerprint") }
        }
        if (existing != null) {
            if (existing.second != fingerprint) throw conflict("idempotency key already used with a different request")
            val summary = svc.db.read { c -> orderSummary(c, existing.first, uid) } ?: throw conflict("order vanished")
            call.respond(HttpStatusCode.OK, withReplayed(summary, true))
            return@post
        }

        val created = svc.db.transaction { c ->
            val address = c.selectOne(
                "SELECT consignee, address, mobile FROM ecs_user_address WHERE address_id = ? AND user_id = ?",
                listOf(req.address_id, uid),
            ) { rs -> Triple(rs.str("consignee"), rs.str("address"), rs.str("mobile")) }
                ?: throw notFound("address not found")
            val shipping = c.selectOne(
                "SELECT shipping_fee FROM ecs_shipping WHERE shipping_id = ? AND enabled = 1",
                listOf(req.shipping_id),
            ) { rs -> rs.cents("shipping_fee") } ?: throw notFound("shipping method not found")
            val payment = c.selectOne(
                "SELECT pay_fee FROM ecs_payment WHERE pay_id = ? AND enabled = 1",
                listOf(req.payment_id),
            ) { rs -> rs.cents("pay_fee") } ?: throw notFound("payment method not found")
            val lines = c.select(
                CART_COLS + CART_FROM + "WHERE c.user_id = ? ORDER BY c.rec_id",
                listOf(uid),
            ) { rs -> OrderLine(rs) }
            if (lines.isEmpty()) throw invalidState("cart is empty or contains no sellable goods")

            for (line in lines) {
                val decremented = c.execute(
                    "UPDATE ecs_goods SET goods_number = goods_number - ? WHERE goods_id = ? AND goods_number >= ?",
                    listOf(line.quantity, line.goodsId, line.quantity),
                )
                if (decremented != 1) throw outOfStock("goods ${line.goodsId} has insufficient stock")
            }

            val goodsAmount = lines.sumOf { line -> line.unitCents * line.quantity }
            val orderAmount = goodsAmount + shipping + payment
            val orderSn = nextOrderSn()
            val orderId = try {
                c.insert(
                    "INSERT INTO ecs_order_info(order_sn, user_id, order_status, consignee, address, mobile, shipping_id, pay_id, goods_amount, shipping_fee, payment_fee, order_amount, idempotency_key, request_fingerprint, remark) " +
                        "VALUES(?, ?, 'pending_payment', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(orderSn, uid, address.first, address.second, address.third, req.shipping_id, req.payment_id, Money.fromCents(goodsAmount), Money.fromCents(shipping), Money.fromCents(payment), Money.fromCents(orderAmount), key, fingerprint, remark),
                )
            } catch (e: org.sqlite.SQLiteException) {
                if (e.message?.contains("UNIQUE") == true) throw conflict("idempotency key already used with a different request")
                throw e
            }
            for (line in lines) {
                c.execute(
                    "INSERT INTO ecs_order_goods(order_id, goods_id, goods_name, goods_number, goods_price) VALUES(?, ?, ?, ?, ?)",
                    listOf(orderId, line.goodsId, line.goodsName, line.quantity, Money.fromCents(line.unitCents)),
                )
            }
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'created', 'order placed via API')",
                listOf(orderId, uid),
            )
            c.execute("DELETE FROM ecs_cart WHERE user_id = ?", listOf(uid))
            orderId
        }
        val summary = svc.db.read { c -> orderSummary(c, created, uid) } ?: throw conflict("order vanished")
        call.respond(HttpStatusCode.Created, withReplayed(summary, false))
    }
}

/** One purchasable cart line materialised inside the order transaction. */
class OrderLine(rs: ResultSet) {
    val goodsId: Long = rs.long("goods_id")
    val goodsName: String = rs.str("goods_name")
    val quantity: Long = rs.long("goods_number")
    val unitPrice: String = displayPriceRs(rs)
    val unitCents: Long = Money.parseCents(unitPrice) ?: 0L
}

private fun nextOrderSn(): String {
    val ts = java.time.LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    val rand = (100000..999999).random()
    return "EC$ts$rand"
}

/** Canonical order summary projection used by every order endpoint. */
internal fun orderSummary(c: java.sql.Connection, orderId: Long, uid: Long): JsonObject? =
    c.selectOne(
        "SELECT order_id, order_sn, order_status, goods_amount, shipping_fee, payment_fee, order_amount, remark, created_at " +
            "FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
        listOf(orderId, uid),
    ) { rs ->
        buildJsonObject {
            put("order_id", rs.long("order_id"))
            put("order_sn", rs.str("order_sn"))
            put("status", rs.str("order_status"))
            put("goods_amount", rs.amount("goods_amount"))
            put("shipping_fee", rs.amount("shipping_fee"))
            put("payment_fee", rs.amount("payment_fee"))
            put("order_amount", rs.amount("order_amount"))
            put("remark", rs.str("remark"))
            put("created_at", rs.long("created_at"))
        }
    }

internal fun withReplayed(summary: JsonObject, replayed: Boolean): JsonObject =
    buildJsonObject {
        for ((k, v) in summary) put(k, v)
        put("replayed", replayed)
    }
