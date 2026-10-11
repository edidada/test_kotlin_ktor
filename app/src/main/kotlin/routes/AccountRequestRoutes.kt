package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.cents
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.long
import ecshop.db.longOrNull
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.db.yuanCents
import ecshop.db.strOrNull
import ecshop.http.Money.fromCents
import ecshop.http.Money.requireCents
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
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — /api/v1/me/account/requests (+/{id}, +/{id}/payment) and /me/account/transactions
 * (maps PHP user.php?act=act_account / account_log / cancel / pay / account_detail).
 * Money is integer cents end to end; amounts cross the API only as decimal strings.
 * Withdrawals freeze balance with a conditional update (available_cents >= amount), so
 * concurrent requests can never overdraw. Cancelling a withdrawal unfreezes in the same
 * transaction as the delete. The payment endpoint is idempotent per request id.
 */
@Serializable
data class AccountRequestPost(val kind: String = "", val amount: String = "", val payment_id: Long? = null, val note: String = "")

@Serializable
data class AccountPaymentRequest(val payment_id: Long = 0)

fun Route.accountRequestRoutes(svc: AppServices) {
    post("/me/account/requests") {
        val uid = requireUserId(svc.db, call)
        val body = call.receive<AccountRequestPost>()
        if (body.kind !in listOf("deposit", "withdrawal")) throw validationError("kind must be deposit or withdrawal", "kind")
        val cents = requireCents(body.amount, "amount")
        if (cents <= 0) throw validationError("amount must be positive", "amount")
        val note = body.note.trim().take(255)
        when (body.kind) {
            "deposit" -> {
                val pid = body.payment_id ?: throw validationError("payment_id is required for deposits", "payment_id")
                val payName = svc.db.read { c ->
                    c.selectOne(
                        "SELECT pay_name FROM ecs_payment WHERE pay_id = ? AND enabled = 1",
                        listOf(pid),
                    ) { it.str("pay_name") }
                } ?: throw validationError("payment method is not available", "payment_id")
                val recId = svc.db.transaction { c ->
                    c.insert(
                        "INSERT INTO ecs_user_account(user_id, amount_cents, process_type, payment_id, user_note, status) VALUES(?, ?, 'deposit', ?, ?, 'pending_payment')",
                        listOf(uid, cents, pid, note),
                    )
                }
                call.respond(
                    HttpStatusCode.Created,
                    buildJsonObject {
                        put("request_id", recId)
                        put("id", recId)
                        put("kind", "deposit")
                        put("amount", fromCents(cents))
                        put("payment_id", pid)
                        put("payment_name", payName)
                        put("status", "pending_payment")
                    },
                )
            }

            else -> {
                if (body.payment_id != null) throw validationError("payment_id is not allowed for withdrawals", "payment_id")
                val now = System.currentTimeMillis() / 1000L
                val result = svc.db.transaction { c ->
                    val frozen = c.execute(
                        "UPDATE ecs_account_balance SET available_cents = available_cents - ?, frozen_cents = frozen_cents + ? WHERE user_id = ? AND available_cents >= ?",
                        listOf(cents, cents, uid, cents),
                    )
                    if (frozen != 1) throw ecshop.http.conflict("available balance is insufficient")
                    val recId = c.insert(
                        "INSERT INTO ecs_user_account(user_id, amount_cents, process_type, payment_id, user_note, status) VALUES(?, ?, 'withdrawal', NULL, ?, 'pending_review')",
                        listOf(uid, cents, note),
                    )
                    c.execute(
                        "INSERT INTO ecs_account_log(user_id, available_delta_cents, frozen_delta_cents, reason, reference_type, reference_id) VALUES(?, ?, ?, 'withdrawal_request', 'user_account', ?)",
                        listOf(uid, -cents, cents, recId),
                    )
                    recId
                }
                call.respond(
                    HttpStatusCode.Created,
                    buildJsonObject {
                    put("id", result)
                        put("request_id", result)
                        put("kind", "withdrawal")
                        put("amount", fromCents(cents))
                        put("status", "pending_review")
                        put("created_at", now)
                    },
                )
            }
        }
    }

    get("/me/account/requests") {
        val uid = requireUserId(svc.db, call)
        val body = svc.db.read { c ->
            val balance = accountBalance(c, uid)
            val items = JsonArray(
                c.select(
                    "SELECT ua.rec_id, ua.process_type, ua.amount_cents, ua.status, ua.payment_id, p.pay_name, ua.user_note, ua.created_at, ua.paid_at " +
                        "FROM ecs_user_account ua LEFT JOIN ecs_payment p ON p.pay_id = ua.payment_id " +
                        "WHERE ua.user_id = ? ORDER BY ua.rec_id",
                    listOf(uid),
                ) { rs ->
                    buildJsonObject {
                        put("request_id", rs.long("rec_id"))
                        put("kind", rs.str("process_type"))
                        put("amount", fromCents(rs.cents("amount_cents")))
                        put("status", rs.str("status"))
                        put("payment_id", rs.longOrNull("payment_id"))
                        put("payment_name", rs.strOrNull("pay_name"))
                        put("note", rs.str("user_note"))
                        put("created_at", rs.long("created_at"))
                        put("paid_at", rs.longOrNull("paid_at"))
                    }
                },
            )
            buildJsonObject {
                put("available_balance", fromCents(balance.first))
                put("frozen_balance", fromCents(balance.second))
                put("items", items)
            }
        }
        call.respond(body)
    }

    delete("/me/account/requests/{id}") {
        val uid = requireUserId(svc.db, call)
        val reqId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("account request not found")
        val now = System.currentTimeMillis() / 1000L
        val outcome = svc.db.transaction { c ->
            val row = c.selectOne(
                "SELECT process_type, amount_cents, status FROM ecs_user_account WHERE rec_id = ? AND user_id = ?",
                listOf(reqId, uid),
            ) { rs -> Triple(rs.str("process_type"), rs.cents("amount_cents"), rs.str("status")) } ?: return@transaction "not_found"
            val cancellable = (row.first == "deposit" && row.third == "pending_payment") ||
                (row.first == "withdrawal" && row.third == "pending_review")
            if (!cancellable) return@transaction "not_found"
            if (row.first == "withdrawal") {
                c.execute(
                    "UPDATE ecs_account_balance SET frozen_cents = frozen_cents - ?, available_cents = available_cents + ? WHERE user_id = ? AND frozen_cents >= ?",
                    listOf(row.second, row.second, uid, row.second),
                )
                c.execute(
                    "INSERT INTO ecs_account_log(user_id, available_delta_cents, frozen_delta_cents, reason, reference_type, reference_id) VALUES(?, ?, ?, 'withdrawal_cancel', 'user_account', ?)",
                    listOf(uid, row.second, -row.second, reqId),
                )
            }
            val deleted = c.execute("DELETE FROM ecs_user_account WHERE rec_id = ? AND user_id = ?", listOf(reqId, uid))
            if (deleted == 1) "cancelled" else "not_found"
        }
        if (outcome != "cancelled") throw notFound("account request not found")
        call.respond(HttpStatusCode.NoContent)
    }

    get("/me/account/transactions") {
        val uid = requireUserId(svc.db, call)
        val body = svc.db.read { c ->
            val balance = accountBalance(c, uid)
            val items = JsonArray(
                c.select(
                    "SELECT log_id, available_delta_cents, frozen_delta_cents, reason, reference_type, reference_id, created_at " +
                        "FROM ecs_account_log WHERE user_id = ? ORDER BY log_id",
                    listOf(uid),
                ) { rs ->
                    buildJsonObject {
                        put("log_id", rs.long("log_id"))
                        put("available_delta", fromCents(rs.cents("available_delta_cents")))
                        put("frozen_delta", fromCents(rs.cents("frozen_delta_cents")))
                        put("reason", rs.str("reason"))
                        put("reference_type", rs.str("reference_type"))
                        put("reference_id", rs.long("reference_id"))
                        put("created_at", rs.long("created_at"))
                    }
                },
            )
            buildJsonObject {
                put("available_balance", fromCents(balance.first))
                put("frozen_balance", fromCents(balance.second))
                put("items", items)
            }
        }
        call.respond(body)
    }

    post("/me/account/requests/{id}/payment") {
        val uid = requireUserId(svc.db, call)
        val reqId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("account request not found")
        val body = call.receive<AccountPaymentRequest>()
        if (body.payment_id <= 0) throw validationError("payment_id must be positive", "payment_id")
        val now = System.currentTimeMillis() / 1000L
        val prepared = svc.db.transaction { c ->
            val row = c.selectOne(
                "SELECT process_type, amount_cents, status FROM ecs_user_account WHERE rec_id = ? AND user_id = ?",
                listOf(reqId, uid),
            ) { rs -> Triple(rs.str("process_type"), rs.cents("amount_cents"), rs.str("status")) }
            if (row == null || row.first != "deposit" || row.third != "pending_payment") {
                throw notFound("account request not found")
            }
            val payment = c.selectOne(
                "SELECT pay_name, pay_fee FROM ecs_payment WHERE pay_id = ? AND enabled = 1",
                listOf(body.payment_id),
            ) { rs -> rs.str("pay_name") to rs.yuanCents("pay_fee") }
                ?: throw validationError("payment method is not available", "payment_id")
            val feeCents = payment.second
            val total = row.second + feeCents
            c.execute(
                "UPDATE ecs_user_account SET payment_id = ? WHERE rec_id = ? AND user_id = ?",
                listOf(body.payment_id, reqId, uid),
            )
            c.insert(
                "INSERT INTO ecs_account_payment_intent(request_id, user_id, payment_id, amount_cents, fee_cents, status, created_at, updated_at) " +
                    "VALUES(?, ?, ?, ?, ?, 'pending', ?, ?) " +
                    "ON CONFLICT(request_id) DO UPDATE SET payment_id = excluded.payment_id, amount_cents = excluded.amount_cents, fee_cents = excluded.fee_cents, status = 'pending', updated_at = excluded.updated_at",
                listOf(reqId, uid, body.payment_id, row.second, feeCents, now, now),
            )
            Triple(row.second, feeCents, total)
        }
        call.respond(
            buildJsonObject {
                put("request_id", reqId)
                put("payment_id", body.payment_id)
                put("amount", fromCents(prepared.first))
                put("fee", fromCents(prepared.second))
                put("total", fromCents(prepared.third))
                put("status", "pending")
            },
        )
    }
}

private fun accountBalance(c: java.sql.Connection, uid: Long): Pair<Long, Long> =
    c.selectOne(
        "SELECT available_cents, frozen_cents FROM ecs_account_balance WHERE user_id = ?",
        listOf(uid),
    ) { rs -> rs.cents("available_cents") to rs.cents("frozen_cents") } ?: (0L to 0L)
