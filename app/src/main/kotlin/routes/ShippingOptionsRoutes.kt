package ecshop.routes

import ecshop.AppServices
import ecshop.db.amount
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
import ecshop.http.queryLong
import ecshop.http.validationError
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M3 — GET /api/v1/shipping-options (maps the public part of PHP myship.php).
 * Validates the country/province/city/district parent chain level by level, returns the
 * selection, the next-level candidates and enabled shipping methods. Shipping is global-rate
 * here: shipping_area per-region rates are intentionally out of scope.
 */
fun Route.shippingOptionsRoutes(svc: AppServices) {
    get("/shipping-options") {
        val countryId = call.queryLong("country_id") ?: 0L
        val provinceId = call.queryLong("province_id")
        val cityId = call.queryLong("city_id")
        val districtId = call.queryLong("district_id")

        val body = svc.db.read { c ->
            fun regionLevel(id: Long?, parentId: Long, type: Int, label: String, parentLabel: String): Long? {
                if (id == null) return null
                if (parentId == 0L && type > 1) {
                    throw validationError("${parentLabel}_id is required before $label", "${parentLabel}_id")
                }
                val row = c.selectOne(
                    "SELECT parent_id, region_name, region_type FROM ecs_region WHERE region_id = ?",
                    listOf(id),
                ) { rs -> Triple(rs.long("parent_id"), rs.str("region_name"), rs.int("region_type")) }
                    ?: throw validationError("$label not found", "${label}_id")
                if (row.first != parentId) throw validationError("$label does not belong to the selected $parentLabel", "${label}_id")
                return id
            }
            // Type check via parent chain: country must be a top-level region.
            if (countryId != 0L) {
                val country = c.selectOne(
                    "SELECT parent_id FROM ecs_region WHERE region_id = ? AND region_type = 1",
                    listOf(countryId),
                ) { it.long("parent_id") } ?: throw validationError("country not found", "country_id")
                if (country != 0L) throw validationError("country must be a top-level region", "country_id")
            }
            val province = regionLevel(provinceId, countryId, 2, "province", "country")
            val city = regionLevel(cityId, province ?: 0L, 3, "city", "province")
            val district = regionLevel(districtId, city ?: 0L, 4, "district", "city")

            fun children(parent: Long): JsonArray = JsonArray(
                c.select(
                    "SELECT region_id, region_name FROM ecs_region WHERE parent_id = ? ORDER BY region_id",
                    listOf(parent),
                ) { rs ->
                    buildJsonObject {
                        put("region_id", rs.long("region_id"))
                        put("name", rs.str("region_name"))
                    }
                },
            )

            buildJsonObject {
                put("selected", buildJsonObject {
                    put("country_id", countryId)
                    put("province_id", province ?: 0L)
                    put("city_id", city ?: 0L)
                    put("district_id", district ?: 0L)
                })
                put("candidates", buildJsonObject {
                    put("countries", children(0L))
                    put("provinces", if (countryId != 0L) children(countryId) else JsonArray(emptyList()))
                    put("cities", if (province != null) children(province) else JsonArray(emptyList()))
                    put("districts", if (city != null) children(city) else JsonArray(emptyList()))
                })
                put("shipping_options", JsonArray(
                    c.select(
                        "SELECT shipping_id, shipping_name, shipping_fee FROM ecs_shipping WHERE enabled = 1 " +
                            "ORDER BY shipping_id",
                    ) { rs ->
                        buildJsonObject {
                            put("shipping_id", rs.long("shipping_id"))
                            put("name", rs.str("shipping_name"))
                            put("fee", rs.amount("shipping_fee"))
                        }
                    },
                ))
            }
        }
        call.respond(body)
    }
}
