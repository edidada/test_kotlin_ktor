package ecshop.routes

import ecshop.AppServices
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.parsePaging
import ecshop.http.pathLong
import ecshop.http.queryLong
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/promotions and /promotions/{id} (maps group_buy.php / auction.php /
 * snatch.php read paths onto goods_activity). Only active-window, unfinished activities whose
 * goods stay visible; ext_info keeps its raw JSON for future specialized endpoints.
 */
fun Route.promotionRoutes(svc: AppServices) {
    get("/promotions") {
        val type = call.queryLong("type")
        if (type != null && type !in 0..4) throw validationError("type must be between 0 and 4", "type")
        val now = System.currentTimeMillis() / 1000L
        val paging = call.parsePaging()
        val body = svc.db.read { c ->
            val where = StringBuilder(
                "a.start_time <= ? AND a.end_time >= ? AND a.is_finished = 0 " +
                    "AND g.is_on_sale = 1 AND g.is_delete = 0",
            )
            val params = ArrayList<Any?>(listOf(now, now))
            if (type != null) {
                where.append(" AND a.act_type = ?")
                params.add(type)
            }
            val whereSql = where.toString()
            val total = c.selectOne(
                "SELECT COUNT(*) AS total FROM ecs_goods_activity a JOIN ecs_goods g ON g.goods_id = a.goods_id " +
                    "WHERE $whereSql",
                params,
            ) { it.long("total") } ?: 0L
            val items = JsonArray(
                c.select(
                    "SELECT a.act_id, a.act_name, a.act_desc, a.act_type, a.goods_id, a.goods_name, " +
                        "a.start_time, a.end_time, a.ext_info " +
                        "FROM ecs_goods_activity a JOIN ecs_goods g ON g.goods_id = a.goods_id " +
                        "WHERE $whereSql ORDER BY a.end_time, a.act_id LIMIT ? OFFSET ?",
                    params + listOf(paging.limit, paging.offset),
                ) { rs -> promotionJson(rs) },
            )
            buildJsonObject {
                put("page", paging.page)
                put("page_size", paging.pageSize)
                put("total", total)
                put("items", items)
            }
        }
        call.respond(body)
    }

    get("/promotions/{id}") {
        val actId = call.pathLong("id")
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            c.selectOne(
                "SELECT a.act_id, a.act_name, a.act_desc, a.act_type, a.goods_id, a.goods_name, " +
                    "a.start_time, a.end_time, a.ext_info " +
                    "FROM ecs_goods_activity a JOIN ecs_goods g ON g.goods_id = a.goods_id " +
                    "WHERE a.act_id = ? AND a.start_time <= ? AND a.end_time >= ? AND a.is_finished = 0 " +
                    "AND g.is_on_sale = 1 AND g.is_delete = 0",
                listOf(actId, now, now),
            ) { rs -> promotionJson(rs) } ?: throw notFound("promotion not found")
        }
        call.respond(body)
    }
}

private fun promotionJson(rs: java.sql.ResultSet) = buildJsonObject {
    put("act_id", rs.long("act_id"))
    put("act_name", rs.str("act_name"))
    put("act_desc", rs.str("act_desc"))
    put("act_type", rs.long("act_type"))
    put("goods_id", rs.long("goods_id"))
    put("goods_name", rs.str("goods_name"))
    put("start_time", rs.long("start_time"))
    put("end_time", rs.long("end_time"))
    val ext = rs.str("ext_info")
    val parsed: JsonElement? = if (ext.isBlank()) null else runCatching { Json.parseToJsonElement(ext) }.getOrNull()
    put("ext_info", parsed ?: kotlinx.serialization.json.JsonPrimitive(ext))
}
