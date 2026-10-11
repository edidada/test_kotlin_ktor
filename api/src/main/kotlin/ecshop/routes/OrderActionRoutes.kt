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
import ecshop.http.requireUserId
import ecshop.http.sha256Hex
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — POST /api/v1/me/orders/{id}/cancel, /received, /cart and POST /me/orders/merge
 * (maps PHP user.php?act=cancel_order / affirm_received / return_to_cart / merge_order).
 * All state transitions use conditional UPDATEs on (order_id, user_id, expected status), so
 * concurrent calls can never double-refund, double-restore stock, or flip a paid order.
 * Balance refunds, stock restores and audit rows share one transaction with the flip.
 */
@Serializable
data class MergeOrdersRequest(val from_order_id: Long = 0, val to_order_id: Long = 0)

fun Route.orderActionRoutes(svc: AppServices) {
    post("/me/orders/{id}/cancel") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        svc.db.transaction { c ->
            val status = c.selectOne(
                "SELECT order_status FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { it.str("order_status") } ?: throw notFound("order not found")
            if (status != "pending_payment") throw conflict("only pending_payment orders can be cancelled")
            val flipped = c.execute(
                "UPDATE ecs_order_info SET order_status = 'cancelled' WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'",
                listOf(orderId, uid),
            )
            if (flipped != 1) throw conflict("order status changed concurrently")
            val lines = c.select(
                "SELECT goods_id, goods_number FROM ecs_order_goods WHERE order_id = ?",
                listOf(orderId),
            ) { rs -> rs.long("goods_id") to rs.long("goods_number") }
            for ((goodsId, qty) in lines) {
                c.execute("UPDATE ecs_goods SET goods_number = goods_number + ? WHERE goods_id = ?", listOf(qty, goodsId))
            }
            val paidCents = c.selectOne(
                "SELECT paid_cents FROM ecs_order_balance_payment WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { it.cents("paid_cents") } ?: 0L
            if (paidCents > 0) {
                c.execute(
                    "UPDATE ecs_account_balance SET available_cents = available_cents + ? WHERE user_id = ?",
                    listOf(paidCents, uid),
                )
                c.execute(
                    "INSERT INTO ecs_account_log(user_id, available_delta_cents, frozen_delta_cents, reason, reference_type, reference_id) VALUES(?, ?, 0, 'order_cancel_refund', 'order_info', ?)",
                    listOf(uid, paidCents, orderId),
                )
                c.execute("DELETE FROM ecs_order_balance_payment WHERE order_id = ? AND user_id = ?", listOf(orderId, uid))
            }
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'cancelled', 'cancelled by customer')",
                listOf(orderId, uid),
            )
        }
        val summary = svc.db.read { c -> orderSummary(c, orderId, uid) } ?: throw notFound("order not found")
        call.respond(summary)
    }

    post("/me/orders/{id}/received") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        svc.db.transaction { c ->
            val exists = c.selectOne(
                "SELECT order_status FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { it.str("order_status") } ?: throw notFound("order not found")
            if (exists != "paid") throw conflict("only paid orders can be affirmed")
            val flipped = c.execute(
                "UPDATE ecs_order_info SET order_status = 'received' WHERE order_id = ? AND user_id = ? AND order_status = 'paid'",
                listOf(orderId, uid),
            )
            if (flipped != 1) throw conflict("order status changed concurrently")
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'received', 'delivery affirmed by customer')",
                listOf(orderId, uid),
            )
        }
        val summary = svc.db.read { c -> orderSummary(c, orderId, uid) } ?: throw notFound("order not found")
        call.respond(summary)
    }

    post("/me/orders/{id}/cart") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        svc.db.transaction { c ->
            val owned = c.selectOne(
                "SELECT order_id FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { it.long("order_id") } ?: throw notFound("order not found")
            val lines = c.select(
                "SELECT og.goods_id, og.goods_number FROM ecs_order_goods og " +
                    "JOIN ecs_goods g ON g.goods_id = og.goods_id AND g.is_on_sale = 1 AND g.is_delete = 0 AND g.goods_number > 0 " +
                    "WHERE og.order_id = ?",
                listOf(owned),
            ) { rs -> rs.long("goods_id") to rs.long("goods_number") }
            if (lines.isEmpty()) throw invalidState("no sellable in-stock goods to return")
            var merged = 0
            for ((goodsId, orderQty) in lines) {
                val stock = c.selectOne("SELECT goods_number FROM ecs_goods WHERE goods_id = ?", listOf(goodsId)) { it.long("goods_number") } ?: 0L
                val inCart = c.selectOne("SELECT goods_number FROM ecs_cart WHERE user_id = ? AND goods_id = ?", listOf(uid, goodsId)) { it.long("goods_number") } ?: 0L
                val add = minOf(orderQty, stock - inCart)
                if (add <= 0) continue
                c.execute(
                    "INSERT INTO ecs_cart(user_id, goods_id, goods_number, version) VALUES(?, ?, ?, 1) " +
                        "ON CONFLICT(user_id, goods_id) DO UPDATE SET goods_number = goods_number + ?, version = version + 1",
                    listOf(uid, goodsId, add, add),
                )
                merged += 1
            }
            if (merged == 0) throw invalidState("cart already covers available stock")
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'returned_to_cart', 'goods merged back into cart')",
                listOf(orderId, uid),
            )
        }
        val body = svc.db.read { c ->
            var totalQty = 0L
            val items = JsonArray(
                c.select(
                    CART_COLS + CART_FROM + "WHERE c.user_id = ? ORDER BY c.rec_id",
                    listOf(uid),
                ) { rs ->
                    totalQty += rs.long("goods_number")
                    cartLinePublic(rs)
                },
            )
            buildJsonObject {
                put("items", items)
                put("total_quantity", totalQty)
            }
        }
        call.respond(body)
    }

    post("/me/orders/merge") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<MergeOrdersRequest>()
        if (req.from_order_id <= 0 || req.to_order_id <= 0 || req.from_order_id == req.to_order_id) {
            throw validationError("from_order_id and to_order_id must be positive and different", "from_order_id")
        }
        val newId = svc.db.transaction { c ->
            for (id in listOf(req.from_order_id, req.to_order_id)) {
                val status = c.selectOne(
                    "SELECT order_status FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                    listOf(id, uid),
                ) { it.str("order_status") } ?: throw notFound("order not found")
                if (status != "pending_payment") throw conflict("both orders must be pending_payment")
                val used = c.selectOne(
                    "SELECT paid_cents FROM ecs_order_balance_payment WHERE order_id = ? AND user_id = ?",
                    listOf(id, uid),
                ) { it.cents("paid_cents") } ?: 0L
                if (used > 0) throw conflict("orders with account balance applied cannot be merged")
            }
            val to = c.selectOne(
                "SELECT order_sn, consignee, address, mobile, shipping_id, pay_id, shipping_fee, payment_fee, remark " +
                    "FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(req.to_order_id, uid),
            ) { rs ->
                listOf(rs.str("consignee"), rs.str("address"), rs.str("mobile"), rs.long("shipping_id"), rs.long("pay_id"), rs.amount("shipping_fee"), rs.amount("payment_fee"), rs.str("remark"))
            } ?: throw notFound("order not found")
            val goodsCents = c.select(
                "SELECT goods_amount FROM ecs_order_info WHERE order_id IN (?, ?) AND user_id = ?",
                listOf(req.from_order_id, req.to_order_id, uid),
            ) { rs -> Money.parseCents(rs.amount("goods_amount")) ?: 0L }.sum()
            val shipCents = Money.parseCents(to[5].toString()) ?: 0L
            val payCents = Money.parseCents(to[6].toString()) ?: 0L
            val orderSn = "EC" + java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) + (100000..999999).random()
            val newOrderId = c.insert(
                "INSERT INTO ecs_order_info(order_sn, user_id, order_status, consignee, address, mobile, shipping_id, pay_id, goods_amount, shipping_fee, payment_fee, order_amount, idempotency_key, request_fingerprint, remark) " +
                    "VALUES(?, ?, 'pending_payment', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(orderSn, uid, to[0], to[1], to[2], to[3], to[4], Money.fromCents(goodsCents), to[5], to[6], Money.fromCents(goodsCents + shipCents + payCents), "merge:$orderSn", sha256Hex("merge|${req.from_order_id}|${req.to_order_id}|$orderSn"), to[7]),
            )
            for (src in listOf(req.from_order_id, req.to_order_id)) {
                val lines = c.select(
                    "SELECT goods_id, goods_name, goods_number, goods_price FROM ecs_order_goods WHERE order_id = ?",
                    listOf(src),
                ) { rs -> listOf(rs.long("goods_id"), rs.str("goods_name"), rs.long("goods_number"), rs.amount("goods_price")) }
                for (line in lines) {
                    c.execute(
                        "INSERT INTO ecs_order_goods(order_id, goods_id, goods_name, goods_number, goods_price) VALUES(?, ?, ?, ?, ?)",
                        listOf(newOrderId, line[0], line[1], line[2], line[3]),
                    )
                }
                c.execute("DELETE FROM ecs_order_goods WHERE order_id = ?", listOf(src))
                c.execute("DELETE FROM ecs_pay_log WHERE order_id = ?", listOf(src))
                c.execute("DELETE FROM ecs_order_action WHERE order_id = ?", listOf(src))
                c.execute("DELETE FROM ecs_order_balance_payment WHERE order_id = ?", listOf(src))
                c.execute("DELETE FROM ecs_order_delivery_address WHERE order_id = ?", listOf(src))
                c.execute("DELETE FROM ecs_order_info WHERE order_id = ? AND user_id = ?", listOf(src, uid))
            }
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'merged', 'orders ${req.from_order_id} + ${req.to_order_id}')",
                listOf(newOrderId, uid),
            )
            newOrderId
        }
        val summary = svc.db.read { c -> orderSummary(c, newId, uid) } ?: throw conflict("merged order vanished")
        call.respond(HttpStatusCode.Created, summary)
    }
}

private fun cartLinePublic(rs: java.sql.ResultSet) = buildJsonObject {
    put("id", rs.long("rec_id"))
    put("goods_id", rs.long("goods_id"))
    put("name", rs.str("goods_name"))
    put("price", displayPriceRs(rs))
    put("quantity", rs.long("goods_number"))
    put("stock", rs.long("stock"))
    put("version", rs.long("version"))
}
