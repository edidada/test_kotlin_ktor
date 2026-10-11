package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.execute
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.conflict
import ecshop.http.notFound
import ecshop.http.requireUserId
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
 * M2 — POST /api/v1/me/bonuses/claim and GET /api/v1/me/bonuses
 * (maps PHP user.php?act=act_add_bonus / bonus).
 * bonus_sn must arrive as a numeric string (1-20 digits) to survive JSON precision.
 * Claiming locks the row and updates with user_id = 0 as the guard condition, so concurrent
 * claims cannot double-assign. Status is computed from DB UTC time, never client time.
 */
@Serializable
data class ClaimBonusRequest(val bonus_sn: String = "")

fun Route.bonusRoutes(svc: AppServices) {
    post("/me/bonuses/claim") {
        val uid = requireUserId(svc.db, call)
        val body = call.receive<ClaimBonusRequest>()
        if (!Regex("^\\d{1,20}$").matches(body.bonus_sn)) {
            throw validationError("bonus_sn must be a 1-20 digit numeric string", "bonus_sn")
        }
        val sn = body.bonus_sn.toLongOrNull() ?: throw notFound("bonus not found")
        val now = System.currentTimeMillis() / 1000L
        svc.db.transaction { c ->
            val row = c.selectOne(
                "SELECT ub.user_id, bt.use_end_date FROM ecs_user_bonus ub " +
                    "JOIN ecs_bonus_type bt ON bt.type_id = ub.bonus_type_id WHERE ub.bonus_sn = ?",
                listOf(sn),
            ) { rs -> rs.long("user_id") to rs.long("use_end_date") } ?: throw notFound("bonus not found")
            if (row.second < now) throw conflict("bonus has expired")
            if (row.first != 0L) throw conflict("bonus already claimed")
            val updated = c.execute(
                "UPDATE ecs_user_bonus SET user_id = ? WHERE bonus_sn = ? AND user_id = 0",
                listOf(uid, sn),
            )
            if (updated != 1) throw conflict("bonus already claimed")
        }
        call.respond(HttpStatusCode.NoContent)
    }

    get("/me/bonuses") {
        val uid = requireUserId(svc.db, call)
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            val items = JsonArray(
                c.select(
                    "SELECT ub.bonus_id, ub.bonus_sn, ub.order_id, bt.type_name, bt.type_money, " +
                        "bt.use_start_date, bt.use_end_date " +
                        "FROM ecs_user_bonus ub JOIN ecs_bonus_type bt ON bt.type_id = ub.bonus_type_id " +
                        "WHERE ub.user_id = ? ORDER BY ub.bonus_id",
                    listOf(uid),
                ) { rs ->
                    val orderId = rs.long("order_id")
                    val useStart = rs.long("use_start_date")
                    val useEnd = rs.long("use_end_date")
                    val status = when {
                        orderId != 0L -> "used"
                        useStart > now -> "not_started"
                        useEnd in 1 until now -> "expired"
                        else -> "available"
                    }
                    buildJsonObject {
                        put("bonus_id", rs.long("bonus_id"))
                        put("bonus_sn", rs.long("bonus_sn"))
                        put("type_name", rs.str("type_name"))
                        put("money", rs.amount("type_money"))
                        put("status", status)
                        put("order_id", if (status == "used") orderId else 0L)
                    }
                },
            )
            buildJsonObject { put("items", items) }
        }
        call.respond(body)
    }
}
