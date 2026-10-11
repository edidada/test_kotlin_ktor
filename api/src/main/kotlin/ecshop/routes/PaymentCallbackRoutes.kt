package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.long
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.db.yuanCents
import ecshop.http.Money
import ecshop.http.conflict
import ecshop.http.notFound
import ecshop.http.unauthorized
import ecshop.http.validationError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * M2 — POST /api/v1/payments/{provider}/callback (maps PHP respond.php and the legacy
 * receive.php / chinabank_receive.php gateway entry points).
 * No user bearer token: the adapter signs the raw JSON body with HMAC-SHA256 and ships the
 * hex digest in X-Payment-Signature. Verification happens over the exact bytes received, the
 * amount must equal the order's current payable total, and the (provider, trade_no) unique
 * key makes replay idempotent: same trade, same order, same amount -> 200; a reused trade no
 * on different content -> 409. The pending_payment -> paid flip is a conditional update, so
 * concurrent callbacks cannot double-pay an order.
 */
fun Route.paymentCallbackRoutes(svc: AppServices) {
    post("/payments/{provider}/callback") {
        val provider = call.parameters["provider"]?.takeIf { it.matches(Regex("^[a-z0-9_]{1,60}$")) }
            ?: throw validationError("provider name is invalid", "provider")
        val raw = call.receiveText()
        val signature = call.request.headers["X-Payment-Signature"]?.lowercase()?.takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }
            ?: throw unauthorized("missing or malformed X-Payment-Signature")
        val expected = hmacSha256Hex(svc.config.paymentCallbackHmacSecret, raw)
        if (!MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), signature.toByteArray(Charsets.US_ASCII))) {
            throw unauthorized("signature verification failed")
        }
        val payload = runCatching {
            val el = Json.parseToJsonElement(raw).jsonObject
            Triple(
                el["provider_trade_no"]?.jsonPrimitive?.content ?: "",
                el["order_sn"]?.jsonPrimitive?.content ?: "",
                el["amount"]?.jsonPrimitive?.content ?: "",
            )
        }.getOrNull() ?: throw validationError("payload must be a JSON object with provider_trade_no, order_sn and amount")
        val (tradeNo, orderSn, amountRaw) = payload
        if (tradeNo.isEmpty() || tradeNo.length > 120) throw validationError("provider_trade_no is required (max 120)", "provider_trade_no")
        if (orderSn.isEmpty() || orderSn.length > 40) throw validationError("order_sn is required (max 40)", "order_sn")
        val paidCents = Money.parseCents(amountRaw) ?: throw validationError("amount must be a decimal string", "amount")

        val outcome = svc.db.transaction { c ->
            val order = c.selectOne(
                "SELECT order_id, user_id, order_status, order_amount FROM ecs_order_info WHERE order_sn = ?",
                listOf(orderSn),
            ) { rs -> listOf(rs.long("order_id"), rs.long("user_id"), rs.str("order_status"), rs.yuanCents("order_amount")) }
                ?: throw notFound("order not found")
            val orderId = order[0]
            if (order[3] != paidCents) throw conflict("amount does not match the order total")
            val existing = c.selectOne(
                "SELECT order_id, amount FROM ecs_pay_log WHERE provider = ? AND provider_trade_no = ?",
                listOf(provider, tradeNo),
            ) { rs -> Pair(rs.long("order_id"), Money.parseCents(rs.amount("amount")) ?: -1L) }
            if (existing != null) {
                if (existing.first == orderId && existing.second == paidCents) return@transaction "replayed"
                throw conflict("trade number conflicts with an existing payment")
            }
            val flipped = c.execute(
                "UPDATE ecs_order_info SET order_status = 'paid' WHERE order_id = ? AND order_status = 'pending_payment'",
                listOf(orderId),
            )
            if (flipped != 1) throw conflict("order is not in a payable state")
            c.insert(
                "INSERT INTO ecs_pay_log(order_id, provider, provider_trade_no, amount, status, raw_payload) VALUES(?, ?, ?, ?, 'success', ?)",
                listOf(orderId, provider, tradeNo, Money.fromCents(paidCents), raw),
            )
            c.execute(
                "INSERT INTO ecs_order_action(order_id, actor_user_id, action, note) VALUES(?, ?, 'paid', ?)",
                listOf(orderId, order[1], "gateway $provider trade $tradeNo captured via signed callback"),
            )
            "paid"
        }
        if (outcome == "replayed") {
            call.respond(HttpStatusCode.OK, buildJsonObject {
                put("status", "already_processed")
                put("replayed", true)
            })
        } else {
            call.respond(HttpStatusCode.OK, buildJsonObject {
                put("status", "paid")
                put("order_sn", orderSn)
            })
        }
    }
}

private fun hmacSha256Hex(secret: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
