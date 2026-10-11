package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.Paging
import ecshop.http.listEnvelope
import ecshop.http.parsePaging
import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.ResultSet

/** Shared ECSHOP `goods` row projection used by every goods listing endpoint. */

/** ECShop price rule: within an active promotion window, promote_price replaces shop_price. */
fun displayPriceRs(rs: ResultSet): String {
    val now = System.currentTimeMillis() / 1000L
    val promotePrice = rs.amount("promote_price")
    val inWindow = rs.int("is_promote") == 1 &&
        now in rs.long("promote_start_date")..rs.long("promote_end_date")
    return if (inWindow && promotePrice != "0.00") promotePrice else rs.amount("shop_price")
}

fun goodsListJson(rs: ResultSet): JsonObject = buildJsonObject {
    put("goods_id", rs.long("goods_id"))
    put("goods_sn", rs.str("goods_sn"))
    put("cat_id", rs.long("cat_id"))
    put("name", rs.str("goods_name"))
    put("brand_id", rs.long("brand_id"))
    put("price", displayPriceRs(rs))
    put("market_price", rs.amount("market_price"))
    put("stock", rs.long("goods_number"))
    put("thumb", rs.str("goods_thumb"))
    put("is_best", rs.int("is_best") == 1)
    put("is_new", rs.int("is_new") == 1)
    put("is_hot", rs.int("is_hot") == 1)
    put("is_promote", rs.int("is_promote") == 1)
    put("add_time", rs.long("add_time"))
}

/** Paged listing over a caller-provided SELECT/WHERE plus its COUNT counterpart. */
fun goodsListing(
    svc: AppServices,
    call: ApplicationCall,
    selectSql: String,
    params: List<Any?>,
    totalSql: String,
    totalParams: List<Any?>,
): JsonObject {
    val paging = call.parsePaging()
    return svc.db.read { c ->
        val total = c.selectOne(totalSql, totalParams) { it.long("total") } ?: 0L
        val items = JsonArray(
            c.select("$selectSql LIMIT ? OFFSET ?", params + listOf(paging.limit, paging.offset)) {
                goodsListJson(it)
            },
        )
        listEnvelope(paging.page, paging.pageSize, total, items)
    }
}

/** A random positive int in [1, bound]; used by the is_random widget selection like PHP's RAND(1,...). */
fun randInt(bound: Int): Int = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, bound + 1)
