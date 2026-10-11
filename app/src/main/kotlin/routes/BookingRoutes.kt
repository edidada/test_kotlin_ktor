package ecshop.routes

import ecshop.AppServices
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
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.sqlite.SQLiteException

/**
 * M2 — GET/POST /api/v1/me/bookings, DELETE /api/v1/me/bookings/{id}
 * (maps PHP user.php?act=booking_list / act_add_booking / act_del_booking).
 * Only sellable goods can be registered and one user may hold exactly one booking per goods;
 * the UNIQUE(user_id, goods_id) index is the dedup guard, so there is no check-then-insert
 * race. The page-level `add_booking` HTML action is replaced by this JSON POST.
 */
@Serializable
data class CreateBookingRequest(
    val goods_id: Long = 0,
    val goods_number: Int = 1,
    val description: String = "",
    val linkman: String = "",
    val email: String = "",
    val telephone: String = "",
)

fun Route.bookingRoutes(svc: AppServices) {
    get("/me/bookings") {
        val uid = requireUserId(svc.db, call)
        val items = svc.db.read { c ->
            JsonArray(
                c.select(
                    "SELECT b.rec_id, b.goods_id, g.goods_name, b.goods_number, b.goods_desc, b.link_man, b.email, b.tel, " +
                        "b.booking_time, b.is_dispose, b.dispose_note " +
                        "FROM ecs_booking_goods b JOIN ecs_goods g ON g.goods_id = b.goods_id " +
                        "WHERE b.user_id = ? ORDER BY b.booking_time DESC, b.rec_id DESC",
                    listOf(uid),
                ) { rs ->
                    buildJsonObject {
                        put("booking_id", rs.long("rec_id"))
                        put("goods_id", rs.long("goods_id"))
                        put("goods_name", rs.str("goods_name"))
                        put("goods_number", rs.long("goods_number"))
                        put("description", rs.str("goods_desc"))
                        put("linkman", rs.str("link_man"))
                        put("email", rs.str("email"))
                        put("telephone", rs.str("tel"))
                        put("booking_time", rs.long("booking_time"))
                        put("disposed", rs.long("is_dispose") == 1L)
                        put("dispose_note", rs.str("dispose_note"))
                    }
                },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }

    post("/me/bookings") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<CreateBookingRequest>()
        if (req.goods_id <= 0) throw validationError("goods_id must be positive", "goods_id")
        if (req.goods_number !in 1..9999) throw validationError("goods_number must be between 1 and 9999", "goods_number")
        val email = req.email.trim().lowercase()
        if (email.isNotEmpty() && !EMAIL_RE.matches(email)) throw validationError("email format is invalid", "email")
        try {
            svc.db.transaction { c ->
                val goodsId = c.selectOne(
                    "SELECT goods_id FROM ecs_goods WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                    listOf(req.goods_id),
                ) { it.long("goods_id") } ?: throw notFound("goods not found")
                c.execute(
                    "INSERT INTO ecs_booking_goods(user_id, email, link_man, tel, goods_id, goods_desc, goods_number, booking_time) VALUES(?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(
                        uid, email, req.linkman.trim().take(60), req.telephone.trim().take(60),
                        goodsId, req.description.trim().take(255), req.goods_number,
                        System.currentTimeMillis() / 1000L,
                    ),
                )
            }
        } catch (e: SQLiteException) {
            if (e.message?.contains("UNIQUE") == true) throw conflict("this goods is already booked")
            throw e
        }
        val created = svc.db.read { c ->
            c.selectOne(
                "SELECT rec_id FROM ecs_booking_goods WHERE user_id = ? AND goods_id = ?",
                listOf(uid, req.goods_id),
            ) { rs -> buildJsonObject { put("booking_id", rs.long("rec_id")) } }
        } ?: throw conflict("booking vanished")
        call.respond(HttpStatusCode.Created, created)
    }

    delete("/me/bookings/{id}") {
        val uid = requireUserId(svc.db, call)
        val recId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("booking not found")
        val deleted = svc.db.transaction { c ->
            c.execute("DELETE FROM ecs_booking_goods WHERE rec_id = ? AND user_id = ?", listOf(recId, uid))
        }
        if (deleted != 1) throw notFound("booking not found")
        call.respond(HttpStatusCode.NoContent)
    }
}
