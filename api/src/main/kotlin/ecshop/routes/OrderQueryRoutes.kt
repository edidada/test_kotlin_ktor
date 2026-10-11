package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.listEnvelope
import ecshop.http.notFound
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
 * M2 — GET /api/v1/me/orders, GET /me/orders/{id},
 * GET /me/orders/by-number/{order_sn}/status
 * (maps PHP user.php?act=order_list / order_detail / order_query).
 * Every SQL predicate carries the Bearer user id, so other users' orders are indistinguishable
 * from missing ones (both 404). The by-number endpoint replaces the old enumerable order-number
 * lookup with token-scoped access.
 */
fun Route.orderQueryRoutes(svc: AppServices) {
    get("/me/orders") {
        val uid = requireUserId(svc.db, call)
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_order_info WHERE user_id = ?",
                listOf(uid),
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT order_id, order_sn, order_status, goods_amount, shipping_fee, payment_fee, order_amount, created_at " +
                        "FROM ecs_order_info WHERE user_id = ? ORDER BY order_id DESC LIMIT ? OFFSET ?",
                    listOf(uid, paging.limit, paging.offset),
                ) { rs ->
                    buildJsonObject {
                        put("order_id", rs.long("order_id"))
                        put("order_sn", rs.str("order_sn"))
                        put("status", rs.str("order_status"))
                        put("goods_amount", rs.amount("goods_amount"))
                        put("shipping_fee", rs.amount("shipping_fee"))
                        put("payment_fee", rs.amount("payment_fee"))
                        put("order_amount", rs.amount("order_amount"))
                        put("created_at", rs.long("created_at"))
                    }
                },
            )
            listEnvelope(paging.page, paging.pageSize, total, items)
        }
        call.respond(body)
    }

    get("/me/orders/{id}") {
        val uid = requireUserId(svc.db, call)
        val orderId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("order not found")
        val body = svc.db.read { c ->
            val order = c.selectOne(
                "SELECT order_id, order_sn, order_status, consignee, address, mobile, shipping_id, pay_id, " +
                    "goods_amount, shipping_fee, payment_fee, order_amount, remark, created_at " +
                    "FROM ecs_order_info WHERE order_id = ? AND user_id = ?",
                listOf(orderId, uid),
            ) { rs ->
                buildJsonObject {
                    put("order_id", rs.long("order_id"))
                    put("order_sn", rs.str("order_sn"))
                    put("status", rs.str("order_status"))
                    put("consignee", rs.str("consignee"))
                    put("address", rs.str("address"))
                    put("mobile", rs.str("mobile"))
                    put("shipping_id", rs.long("shipping_id"))
                    put("payment_id", rs.long("pay_id"))
                    put("goods_amount", rs.amount("goods_amount"))
                    put("shipping_fee", rs.amount("shipping_fee"))
                    put("payment_fee", rs.amount("payment_fee"))
                    put("order_amount", rs.amount("order_amount"))
                    put("remark", rs.str("remark"))
                    put("created_at", rs.long("created_at"))
                }
            } ?: throw notFound("order not found")
            val items = JsonArray(
                c.select(
                    "SELECT rec_id, goods_id, goods_name, goods_number, goods_price FROM ecs_order_goods WHERE order_id = ? ORDER BY rec_id",
                    listOf(orderId),
                ) { rs ->
                    buildJsonObject {
                        put("rec_id", rs.long("rec_id"))
                        put("goods_id", rs.long("goods_id"))
                        put("goods_name", rs.str("goods_name"))
                        put("goods_number", rs.long("goods_number"))
                        put("goods_price", rs.amount("goods_price"))
                    }
                },
            )
            buildJsonObject {
                put("order", order)
                put("items", items)
            }
        }
        call.respond(body)
    }

    get("/me/orders/by-number/{order_sn}/status") {
        val uid = requireUserId(svc.db, call)
        val sn = call.parameters["order_sn"]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }
            ?: throw notFound("order not found")
        val body = svc.db.read { c ->
            c.selectOne(
                "SELECT order_id, order_sn, order_status, order_amount, created_at FROM ecs_order_info WHERE order_sn = ? AND user_id = ?",
                listOf(sn, uid),
            ) { rs ->
                buildJsonObject {
                    put("order_id", rs.long("order_id"))
                    put("order_sn", rs.str("order_sn"))
                    put("status", rs.str("order_status"))
                    put("order_amount", rs.amount("order_amount"))
                    put("created_at", rs.long("created_at"))
                }
            } ?: throw notFound("order not found")
        }
        call.respond(body)
    }
}
