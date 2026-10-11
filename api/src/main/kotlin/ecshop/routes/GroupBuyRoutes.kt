package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.requireUserId
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — GET /api/v1/me/group-buys and GET /api/v1/me/group-buys/{id}
 * (maps PHP user.php?act=group_buy / group_buy_detail, which were placeholder code).
 * An order counts as a group-buy order only when it is linked through order_promotion to a
 * goods_activity with act_type = 1, so plain purchases of the same goods are never
 * misclassified. History survives activity expiry: no current-window filter is applied here,
 * and foreign or missing records always answer 404.
 */
private const val GB_SELECT =
    "SELECT ga.act_id, ga.act_name, ga.act_desc, ga.start_time, ga.end_time, ga.is_finished, ga.ext_info, " +
        "oi.order_id, oi.order_sn, oi.order_status, oi.order_amount, oi.created_at " +
        "FROM ecs_order_info oi " +
        "JOIN ecs_order_promotion op ON op.order_id = oi.order_id AND op.promotion_type = 'group_buy' " +
        "JOIN ecs_goods_activity ga ON ga.act_id = op.promotion_id AND ga.act_type = 1 "

fun Route.groupBuyRoutes(svc: AppServices) {
    get("/me/group-buys") {
        val uid = requireUserId(svc.db, call)
        val items = svc.db.read { c ->
            val rows = c.select(
                GB_SELECT + "WHERE oi.user_id = ? ORDER BY ga.act_id DESC, oi.order_id DESC",
                listOf(uid),
            ) { rs ->
                GroupBuyRow(
                    actId = rs.long("act_id"), actName = rs.str("act_name"), actDesc = rs.str("act_desc"),
                    startTime = rs.long("start_time"), endTime = rs.long("end_time"), finished = rs.int("is_finished") == 1,
                    orderId = rs.long("order_id"), orderSn = rs.str("order_sn"), status = rs.str("order_status"),
                    amount = rs.amount("order_amount"), createdAt = rs.long("created_at"),
                )
            }
            val grouped = rows.groupBy { it.actId }
            JsonArray(
                grouped.map { (actId, list) ->
                    buildJsonObject {
                        put("promotion_id", actId)
                        put("act_name", list.first().actName)
                        put("act_desc", list.first().actDesc)
                        put("start_time", list.first().startTime)
                        put("end_time", list.first().endTime)
                        put("is_finished", list.first().finished)
                        put("orders", JsonArray(list.map { orderJson(it) }))
                    }
                },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }

    get("/me/group-buys/{id}") {
        val uid = requireUserId(svc.db, call)
        val actId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("group buy not found")
        val body = svc.db.read { c ->
            val rows = c.select(
                GB_SELECT + "WHERE oi.user_id = ? AND ga.act_id = ? ORDER BY oi.order_id DESC",
                listOf(uid, actId),
            ) { rs ->
                GroupBuyRow(
                    actId = rs.long("act_id"), actName = rs.str("act_name"), actDesc = rs.str("act_desc"),
                    startTime = rs.long("start_time"), endTime = rs.long("end_time"), finished = rs.int("is_finished") == 1,
                    orderId = rs.long("order_id"), orderSn = rs.str("order_sn"), status = rs.str("order_status"),
                    amount = rs.amount("order_amount"), createdAt = rs.long("created_at"),
                )
            }
            if (rows.isEmpty()) throw notFound("group buy not found")
            val head = rows.first()
            buildJsonObject {
                put("promotion_id", head.actId)
                put("act_name", head.actName)
                put("act_desc", head.actDesc)
                put("start_time", head.startTime)
                put("end_time", head.endTime)
                put("is_finished", head.finished)
                put("orders", JsonArray(rows.map { orderJson(it) }))
            }
        }
        call.respond(body)
    }
}

private data class GroupBuyRow(
    val actId: Long,
    val actName: String,
    val actDesc: String,
    val startTime: Long,
    val endTime: Long,
    val finished: Boolean,
    val orderId: Long,
    val orderSn: String,
    val status: String,
    val amount: String,
    val createdAt: Long,
)

private fun orderJson(row: GroupBuyRow) = buildJsonObject {
    put("order_id", row.orderId)
    put("order_sn", row.orderSn)
    put("status", row.status)
    put("order_amount", row.amount)
    put("created_at", row.createdAt)
}
