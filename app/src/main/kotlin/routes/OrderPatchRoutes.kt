package ecshop.routes

import ecshop.AppServices
import ecshop.db.cents
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.long
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.Money
import ecshop.http.conflict
import ecshop.http.notFound
import ecshop.http.requireUserId
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.patch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — PATCH /api/v1/me/orders/{id}/surplus, /payment and /address
 * (maps PHP user.php?act=act_edit_surplus / act_edit_payment / save_order_address).
 * Surplus payment moves real cents from account_balance into order_balance_payment with a
 * conditional deduction, truncates to the outstanding goods amount, and can atomically flip
 * the order to paid. Switching payment recomputes the fixed gateway fee off the outstanding
 * base. All three are owner-scoped conditional updates: status races return 409, foreign or
 * missing orders 404.
 */
@Serializable
data class SurplusRequest(val amount: String = "")

@Serializable
data class OrderPaymentSwitchRequest(val payment_id: Long = 0)

@Serializable
data class OrderAddressRequest(
    val consignee: String = "",
    val email: String = "",
    val address: String = "",
    val zipcode: String = "",
    val tel: String = "",
    val mobile: String = "",
    val sign_building: String = "",
    val best_time: String = "",
)

fun Route.orderPatchRoutes(svc: AppServices) {
    patch("/me/orders/{id}/surplus") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        val req = call.receive<SurplusRequest>()
        val requested = Money.requireCents(req.amount, "amount")
        if (requested <= 0) throw validationError("amount must be positive", "amount")
        val result = svc.db.transaction { c ->
            val order = c.selectOne(
                "SELECT order_status, payment_fee, order_amount FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { rs -> Triple(rs.str("order_status"), rs.cents("payment_fee"), rs.cents("order_amount")) } ?: throw notFound("order not found")
            if (order.first != "pending_payment") throw conflict("order is not awaiting payment")
            val outstanding = order.third - order.second
            if (outstanding <= 0L) throw conflict("order balance is already settled")
            val applied = minOf(requested, outstanding)
            val deducted = c.execute(
                "UPDATE ecs_account_balance SET available_cents = available_cents - ? WHERE user_id = ? AND available_cents >= ?",
                listOf(applied, uid, applied),
            )
            if (deducted != 1) throw conflict("account balance is insufficient")
            c.insert(
                "INSERT INTO ecs_account_log(user_id, available_delta_cents, frozen_delta_cents, reason, reference_type, reference_id) VALUES(?, ?, 0, 'order_surplus_payment', 'order_info', ?)",
                listOf(uid, -applied, orderId),
            )
            c.execute(
                "INSERT INTO ecs_order_balance_payment(order_id, user_id, paid_cents) VALUES(?, ?, ?) ON CONFLICT(order_id) DO UPDATE SET paid_cents = paid_cents + excluded.paid_cents, updated_at = unixepoch()",
                listOf(orderId, uid, applied),
            )
            val newAmount = order.third - applied
            val flippedToPaid = outstanding - applied == 0L
            val updated = if (flippedToPaid) {
                c.execute(
                    "UPDATE ecs_order_info SET order_amount = ?, order_status = 'paid' WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'",
                    listOf(Money.fromCents(newAmount), orderId, uid),
                )
            } else {
                c.execute(
                    "UPDATE ecs_order_info SET order_amount = ? WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'",
                    listOf(Money.fromCents(newAmount), orderId, uid),
                )
            }
            if (updated != 1) throw conflict("order status changed concurrently")
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'surplus_paid', ?)",
                listOf(orderId, uid, "account balance applied: ${Money.fromCents(applied)}"),
            )
            applied
        }
        val summary = svc.db.read { c -> orderSummary(c, orderId, uid) } ?: throw notFound("order not found")
        call.respond(buildJsonObject {
            put("applied", Money.fromCents(result))
            put("order", summary)
        })
    }

    patch("/me/orders/{id}/payment") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        val req = call.receive<OrderPaymentSwitchRequest>()
        if (req.payment_id <= 0) throw validationError("payment_id must be positive", "payment_id")
        svc.db.transaction { c ->
            val order = c.selectOne(
                "SELECT order_status, pay_id, payment_fee, order_amount FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { rs -> Quad(rs.str("order_status"), rs.long("pay_id"), rs.cents("payment_fee"), rs.cents("order_amount")) } ?: throw notFound("order not found")
            if (order.status != "pending_payment") throw conflict("order is not awaiting payment")
            if (order.payId == req.payment_id) throw conflict("payment method unchanged")
            val newPay = c.selectOne(
                "SELECT pay_fee FROM ecs_payment WHERE pay_id = ? AND enabled = 1",
                listOf(req.payment_id),
            ) { rs -> rs.cents("pay_fee") } ?: throw notFound("payment method not found")
            val base = order.orderAmount - order.paymentFee
            val updated = c.execute(
                "UPDATE ecs_order_info SET pay_id = ?, payment_fee = ?, order_amount = ? WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'",
                listOf(req.payment_id, Money.fromCents(newPay), Money.fromCents(base + newPay), orderId, uid),
            )
            if (updated != 1) throw conflict("order status changed concurrently")
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'payment_changed', ?)",
                listOf(orderId, uid, "payment switched from ${order.payId} to ${req.payment_id}"),
            )
        }
        val summary = svc.db.read { c -> orderSummary(c, orderId, uid) } ?: throw notFound("order not found")
        call.respond(summary)
    }

    patch("/me/orders/{id}/address") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        val req = call.receive<OrderAddressRequest>()
        val consignee = req.consignee.trim().takeIf { it.isNotEmpty() && it.length <= 60 }
            ?: throw validationError("consignee is required (max 60 chars)", "consignee")
        if (!EMAIL_RE.matches(req.email.trim())) throw validationError("email format is invalid", "email")
        val address = req.address.trim().takeIf { it.isNotEmpty() && it.length <= 255 }
            ?: throw validationError("address is required (max 255 chars)", "address")
        val email = req.email.trim().lowercase().take(60)
        val zipcode = req.zipcode.trim().take(60)
        val tel = req.tel.trim().take(60)
        val signBuilding = req.sign_building.trim().take(120)
        val bestTime = req.best_time.trim().take(120)
        val mobile = req.mobile.trim().take(32)
        svc.db.transaction { c ->
            val order = c.selectOne(
                "SELECT order_status FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { it.str("order_status") } ?: throw notFound("order not found")
            if (order != "pending_payment") throw conflict("only pending_payment orders can change address")
            val updated = c.execute(
                "UPDATE ecs_order_info SET consignee = ?, address = ?, mobile = ? WHERE order_id = ? AND user_id = ? AND order_status = 'pending_payment'",
                listOf(consignee, address, mobile, orderId, uid),
            )
            if (updated != 1) throw conflict("order status changed concurrently")
            c.execute(
                "INSERT INTO ecs_order_delivery_address(order_id, user_id, email, zipcode, telephone, sign_building, best_time) VALUES(?, ?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(order_id) DO UPDATE SET email = excluded.email, zipcode = excluded.zipcode, telephone = excluded.telephone, sign_building = excluded.sign_building, best_time = excluded.best_time",
                listOf(orderId, uid, email, zipcode, tel, signBuilding, bestTime),
            )
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'address_updated', 'delivery address saved via API')",
                listOf(orderId, uid),
            )
        }
        val summary = svc.db.read { c -> orderSummary(c, orderId, uid) } ?: throw notFound("order not found")
        call.respond(summary)
    }
}

private class Quad(val status: String, val payId: Long, val paymentFee: Long, val orderAmount: Long)
