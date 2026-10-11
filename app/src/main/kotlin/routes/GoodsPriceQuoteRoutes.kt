package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.notFound
import ecshop.http.pathLong
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal

/**
 * M1 — POST /api/v1/goods/{id}/price-quote (maps PHP goods.php?act=price).
 * Display-only quote: base price + attribute surcharges, SKU stock when product_id given.
 * The client total is never trusted; checkout recalculates from the database.
 */
@Serializable
data class PriceQuoteRequest(
    val quantity: Int = 1,
    val product_id: Long? = null,
    val attribute_ids: List<Long> = emptyList(),
)

fun Route.goodsPriceQuoteRoutes(svc: AppServices) {
    post("/goods/{id}/price-quote") {
        val goodsId = call.pathLong("id")
        val request = call.receive<PriceQuoteRequest>()
        if (request.quantity < 1 || request.quantity > 999) {
            throw validationError("quantity must be between 1 and 999", "quantity")
        }

        val body = svc.db.read { c ->
            val base = c.selectOne(
                "SELECT shop_price, market_price, promote_price, promote_start_date, promote_end_date, " +
                    "is_promote, goods_number FROM ecs_goods " +
                    "WHERE goods_id = ? AND is_on_sale = 1 AND is_delete = 0",
                listOf(goodsId),
            ) { rs ->
                val now = System.currentTimeMillis() / 1000L
                val promote = rs.amount("promote_price")
                val inWindow = rs.int("is_promote") == 1 &&
                    now in rs.long("promote_start_date")..rs.long("promote_end_date")
                val unit = if (inWindow && promote != "0.00") promote else rs.amount("shop_price")
                unit to rs.long("goods_number")
            } ?: throw notFound("goods not found")

            var unitPrice = BigDecimal(base.first)
            var stock = base.second

            if (request.product_id != null) {
                val sku = c.selectOne(
                    "SELECT product_number FROM ecs_products WHERE product_id = ? AND goods_id = ?",
                    listOf(request.product_id, goodsId),
                ) { it.long("product_number") } ?: throw notFound("product not found")
                stock = sku
            }
            if (request.attribute_ids.isNotEmpty()) {
                val placeholders = request.attribute_ids.joinToString(",") { "?" }
                val attrs = c.select(
                    "SELECT goods_attr_id, attr_price FROM ecs_goods_attr " +
                        "WHERE goods_id = ? AND goods_attr_id IN ($placeholders)",
                    listOf(goodsId) + request.attribute_ids,
                ) { rs -> rs.str("attr_price") }
                if (attrs.size != request.attribute_ids.distinct().size) {
                    throw validationError("attribute_ids contain ids that do not belong to this goods", "attribute_ids")
                }
                attrs.forEach { unitPrice = unitPrice.add(BigDecimal(it)) }
            }

            val total = unitPrice.setScale(2, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal(request.quantity))
                .setScale(2, java.math.RoundingMode.HALF_UP)
            buildJsonObject {
                put("goods_id", goodsId)
                put("quantity", request.quantity)
                put("unit_price", unitPrice.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                put("total", total.toPlainString())
                put("currency", "CNY")
                put("stock_available", stock)
            }
        }
        call.respond(body)
    }
}
