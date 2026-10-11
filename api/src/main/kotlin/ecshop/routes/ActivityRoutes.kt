package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/activities (maps PHP activity.php). Current favourable_activity rules in
 * their time window, ordered by sort_order/end_time; raw fields kept so checkout can reuse them.
 */
fun Route.activityRoutes(svc: AppServices) {
    get("/activities") {
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_favourable_activity " +
                    "WHERE start_time <= ? AND end_time >= ?",
                listOf(now, now),
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT act_id, act_name, start_time, end_time, user_rank, act_range, act_range_ext, " +
                        "min_amount, max_amount, act_type, act_type_ext, gift " +
                        "FROM ecs_favourable_activity WHERE start_time <= ? AND end_time >= ? " +
                        "ORDER BY sort_order, end_time, act_id",
                    listOf(now, now),
                ) { rs ->
                    buildJsonObject {
                        put("act_id", rs.long("act_id"))
                        put("act_name", rs.str("act_name"))
                        put("start_time", rs.long("start_time"))
                        put("end_time", rs.long("end_time"))
                        put("user_rank", rs.str("user_rank"))
                        put("range", rs.int("act_range"))
                        put("range_ext", rs.str("act_range_ext"))
                        put("min_amount", rs.amount("min_amount"))
                        put("max_amount", rs.amount("max_amount"))
                        put("act_type", rs.int("act_type"))
                        put("act_type_ext", rs.amount("act_type_ext"))
                        put("gift", rs.str("gift"))
                    }
                },
            )
            buildJsonObject {
                put("total", total)
                put("items", items)
            }
        }
        call.respond(body)
    }
}
