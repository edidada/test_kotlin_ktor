package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.str
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * M3 — GET /api/v1/packages (maps PHP package.php).
 * Ongoing act_type=4 packages within their time window; subtotal is computed from current
 * sellable goods prices, saving is clamped to non-negative. All amounts are 2-decimal strings.
 */
fun Route.packageRoutes(svc: AppServices) {
    get("/packages") {
        val now = System.currentTimeMillis() / 1000L
        val body = svc.db.read { c ->
            val activities = c.select(
                "SELECT act_id, act_name, act_desc, start_time, end_time, ext_info " +
                    "FROM ecs_goods_activity " +
                    "WHERE act_type = 4 AND is_finished = 0 AND start_time <= ? AND end_time >= ? " +
                    "ORDER BY end_time, act_id",
                listOf(now, now),
            ) { rs ->
                PkgAct(
                    actId = rs.long("act_id"),
                    name = rs.str("act_name"),
                    intro = rs.str("act_desc"),
                    start = rs.long("start_time"),
                    end = rs.long("end_time"),
                    extInfo = rs.str("ext_info"),
                )
            }
            val items = JsonArray(
                activities.mapNotNull { act ->
                    val packagePrice = parsePackagePrice(act.extInfo) ?: return@mapNotNull null
                    val lines = c.select(
                        "SELECT pg.goods_id, pg.goods_number, g.goods_name, g.goods_thumb, g.shop_price, " +
                            "g.promote_price, g.promote_start_date, g.promote_end_date, g.is_promote " +
                            "FROM ecs_package_goods pg JOIN ecs_goods g ON g.goods_id = pg.goods_id " +
                            "WHERE pg.package_id = ? AND g.is_on_sale = 1 AND g.is_delete = 0 " +
                            "ORDER BY pg.goods_id",
                        listOf(act.actId),
                    ) { rs ->
                        val promote = rs.amount("promote_price")
                        val inWindow = rs.int("is_promote") == 1 &&
                            now in rs.long("promote_start_date")..rs.long("promote_end_date")
                        PkgLine(
                            goodsId = rs.long("goods_id"),
                            name = rs.str("goods_name"),
                            thumb = rs.str("goods_thumb"),
                            unit = if (inWindow && promote != "0.00") promote else rs.amount("shop_price"),
                            qty = rs.long("goods_number"),
                        )
                    }
                    if (lines.isEmpty()) return@mapNotNull null
                    var subtotal = BigDecimal.ZERO
                    val goodsArr = JsonArray(
                        lines.map { line ->
                            subtotal = subtotal.add(
                                BigDecimal(line.unit).multiply(BigDecimal(line.qty.toString())),
                            )
                            buildJsonObject {
                                put("goods_id", line.goodsId)
                                put("name", line.name)
                                put("thumb", line.thumb)
                                put("price", line.unit)
                                put("quantity", line.qty)
                            }
                        },
                    )
                    val saving = subtotal.setScale(2, RoundingMode.HALF_UP)
                        .subtract(packagePrice)
                        .max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.HALF_UP)
                    buildJsonObject {
                        put("act_id", act.actId)
                        put("name", act.name)
                        put("intro", act.intro)
                        put("start_time", act.start)
                        put("end_time", act.end)
                        put("package_price", packagePrice.setScale(2, RoundingMode.HALF_UP).toPlainString())
                        put("subtotal", subtotal.setScale(2, RoundingMode.HALF_UP).toPlainString())
                        put("saving", saving.toPlainString())
                        put("items", goodsArr)
                    }
                },
            )
            buildJsonObject {
                put("total", items.size.toLong())
                put("items", items)
            }
        }
        call.respond(body)
    }
}

private data class PkgAct(val actId: Long, val name: String, val intro: String, val start: Long, val end: Long, val extInfo: String)

private data class PkgLine(val goodsId: Long, val name: String, val thumb: String, val unit: String, val qty: Long)

/** Reads package_price from the JSON stored in goods_activity.ext_info. */
internal fun parsePackagePrice(extInfo: String): BigDecimal? {
    if (extInfo.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(extInfo) as? JsonObject }.getOrNull() ?: return null
    val raw = (obj["package_price"] as? JsonPrimitive)?.content ?: return null
    return runCatching { BigDecimal(raw) }.getOrNull()
}
