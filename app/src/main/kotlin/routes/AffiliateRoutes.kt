package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.long
import ecshop.db.select
import ecshop.db.str
import ecshop.http.listEnvelope
import ecshop.http.parsePaging
import ecshop.http.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — GET /api/v1/me/affiliate (maps PHP user.php?act=affiliate "my referral" branch).
 * `levels` comes from a recursive CTE over user_referral capped at 10 hops. `orders` merges
 * every referred user's order with the current user's existing affiliate_log split records;
 * order numbers are masked like the legacy page. separated=false means no split row exists
 * for the current user yet; negative separate_type marks a revoked commission.
 */
fun Route.affiliateRoutes(svc: AppServices) {
    get("/me/affiliate") {
        val uid = requireUserId(svc.db, call)
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val levels = JsonArray(
                c.select(
                    "WITH RECURSIVE ref(user_id, depth) AS (" +
                        "SELECT user_id, 1 FROM ecs_user_referral WHERE referrer_user_id = ? " +
                        "UNION ALL SELECT ur.user_id, rf.depth + 1 FROM ecs_user_referral ur JOIN ref rf ON rf.user_id = ur.referrer_user_id WHERE rf.depth < 10) " +
                        "SELECT depth, COUNT(*) AS user_count FROM ref GROUP BY depth ORDER BY depth",
                    listOf(uid),
                ) { rs ->
                    buildJsonObject {
                        put("level", rs.long("depth"))
                        put("user_count", rs.long("user_count"))
                    }
                },
            )
            val referredIds = c.select(
                "WITH RECURSIVE ref(user_id, depth) AS (" +
                    "SELECT user_id, 1 FROM ecs_user_referral WHERE referrer_user_id = ? " +
                    "UNION ALL SELECT ur.user_id, rf.depth + 1 FROM ecs_user_referral ur JOIN ref rf ON rf.user_id = ur.referrer_user_id WHERE rf.depth < 10) " +
                    "SELECT user_id FROM ref",
                listOf(uid),
            ) { it.long("user_id") }
            val orderRows = if (referredIds.isEmpty()) {
                emptyList()
            } else {
                val placeholders = referredIds.joinToString(",") { "?" }
                c.select(
                    "SELECT order_id, order_sn, order_amount, order_status, created_at FROM ecs_order_info " +
                        "WHERE user_id IN ($placeholders) AND order_status <> 'cancelled' ORDER BY order_id DESC",
                    referredIds,
                ) { rs -> AffOrder(rs.long("order_id"), rs.str("order_sn"), rs.amount("order_amount"), rs.long("created_at")) }
            }
            val splits = c.select(
                "SELECT order_id, money, point, separate_type, created_at FROM ecs_affiliate_log WHERE user_id = ? ORDER BY log_id",
                listOf(uid),
            ) { rs ->
                rs.long("order_id") to Triple(rs.amount("money"), rs.long("point"), rs.long("separate_type"))
            }.toMap()
            val merged = JsonArray(
                orderRows.map { row ->
                    val split = splits[row.orderId]
                    buildJsonObject {
                        put("order_id", row.orderId)
                        put("order_sn", maskOrderSn(row.orderSn))
                        put("order_amount", row.orderAmount)
                        put("created_at", row.createdAt)
                        put("separated", split != null)
                        put("commission", split?.first)
                        put("point", split?.second)
                        put("separate_type", split?.third)
                    }
                },
            )
            val total = orderRows.size.toLong()
            val pageItems = JsonArray(
                merged.drop(((paging.page - 1) * paging.pageSize).toInt()).take(paging.pageSize.toInt()),
            )
            buildJsonObject {
                put("levels", levels)
                put("orders", listEnvelope(paging.page, paging.pageSize, total, pageItems))
            }
        }
        call.respond(body)
    }
}

private data class AffOrder(val orderId: Long, val orderSn: String, val orderAmount: String, val createdAt: Long)

private fun maskOrderSn(sn: String): String =
    if (sn.length <= 8) sn else sn.take(6) + "****" + sn.takeLast(2)
