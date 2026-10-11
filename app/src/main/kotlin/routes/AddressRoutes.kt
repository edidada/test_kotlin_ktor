package ecshop.routes

import ecshop.AppServices
import ecshop.db.execute
import ecshop.db.insert
import ecshop.db.int
import ecshop.db.long
import ecshop.db.select
import ecshop.db.selectOne
import ecshop.db.str
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
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * M2 — GET/POST /api/v1/me/addresses, PATCH/DELETE /api/v1/me/addresses/{id}
 * (maps PHP user.php?act=address_list / act_edit_address / drop_consignee).
 * Region ids must form a real country -> province -> city -> district chain in ecs_region;
 * every write is owned-scoped, and the default flag is kept unique per user inside one
 * transaction. Unknown or foreign ids always answer 404.
 */
fun Route.addressRoutes(svc: AppServices) {
    get("/me/addresses") {
        val uid = requireUserId(svc.db, call)
        val items = svc.db.read { c ->
            JsonArray(
                c.select(
                    "SELECT address_id, consignee, country_id, province_id, city_id, district_id, " +
                        "address, zipcode, mobile, is_default FROM ecs_user_address WHERE user_id = ? ORDER BY address_id",
                    listOf(uid),
                ) { rs -> addressJson(rs) },
            )
        }
        call.respond(buildJsonObject { put("items", items) })
    }

    post("/me/addresses") {
        val uid = requireUserId(svc.db, call)
        val req = call.receive<AddressRequest>()
        val fields = validateAddress(req, full = true)
        val id = svc.db.transaction { c ->
            validateRegionChain(c, req.country_id, req.province_id, req.city_id, req.district_id)
            if (fields.isDefault) {
                c.execute("UPDATE ecs_user_address SET is_default = 0 WHERE user_id = ?", listOf(uid))
            }
            c.insert(
                "INSERT INTO ecs_user_address(user_id, consignee, country_id, province_id, city_id, district_id, address, zipcode, mobile, is_default) " +
                    "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(uid, fields.consignee, fields.countryId, fields.provinceId, fields.cityId, fields.districtId, fields.address, fields.zipcode, fields.mobile, if (fields.isDefault) 1 else 0),
            )
        }
        val created = svc.db.read { c ->
            c.selectOne("SELECT address_id, consignee, country_id, province_id, city_id, district_id, address, zipcode, mobile, is_default FROM ecs_user_address WHERE address_id = ? AND user_id = ?", listOf(id, uid)) { rs -> addressJson(rs) }
        } ?: throw notFound("address not found")
        call.respond(HttpStatusCode.Created, created)
    }

    patch("/me/addresses/{id}") {
        val uid = requireUserId(svc.db, call)
        val addrId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("address not found")
        val req = call.receive<AddressRequest>()
        val fields = validateAddress(req, full = true)
        svc.db.transaction { c ->
            val owner = c.selectOne("SELECT address_id FROM ecs_user_address WHERE address_id = ? AND user_id = ?", listOf(addrId, uid)) { it.long("address_id") }
                ?: throw notFound("address not found")
            validateRegionChain(c, req.country_id, req.province_id, req.city_id, req.district_id)
            if (fields.isDefault) {
                c.execute("UPDATE ecs_user_address SET is_default = 0 WHERE user_id = ? AND address_id <> ?", listOf(uid, owner))
            }
            val updated = c.execute(
                "UPDATE ecs_user_address SET consignee = ?, country_id = ?, province_id = ?, city_id = ?, district_id = ?, address = ?, zipcode = ?, mobile = ?, is_default = ? WHERE address_id = ? AND user_id = ?",
                listOf(fields.consignee, fields.countryId, fields.provinceId, fields.cityId, fields.districtId, fields.address, fields.zipcode, fields.mobile, if (fields.isDefault) 1 else 0, addrId, uid),
            )
            if (updated != 1) throw notFound("address not found")
        }
        val updated = svc.db.read { c ->
            c.selectOne("SELECT address_id, consignee, country_id, province_id, city_id, district_id, address, zipcode, mobile, is_default FROM ecs_user_address WHERE address_id = ? AND user_id = ?", listOf(addrId, uid)) { rs -> addressJson(rs) }
        } ?: throw notFound("address not found")
        call.respond(updated)
    }

    delete("/me/addresses/{id}") {
        val uid = requireUserId(svc.db, call)
        val addrId = call.parameters["id"]?.toLongOrNull()?.takeIf { it > 0 } ?: throw notFound("address not found")
        val deleted = svc.db.transaction { c ->
            c.execute("DELETE FROM ecs_user_address WHERE address_id = ? AND user_id = ?", listOf(addrId, uid))
        }
        if (deleted != 1) throw notFound("address not found")
        call.respond(HttpStatusCode.NoContent)
    }
}

@kotlinx.serialization.Serializable
data class AddressRequest(
    val consignee: String? = null,
    val country_id: Long? = null,
    val province_id: Long? = null,
    val city_id: Long? = null,
    val district_id: Long? = null,
    val address: String? = null,
    val mobile: String? = null,
    val zip: String? = null,
    val is_default: Boolean? = null,
)

private class AddressFields(
    val consignee: String,
    val countryId: Long,
    val provinceId: Long,
    val cityId: Long,
    val districtId: Long,
    val address: String,
    val zipcode: String,
    val mobile: String,
    val isDefault: Boolean,
)

private fun validateAddress(req: AddressRequest, full: Boolean): AddressFields {
    val consignee = req.consignee?.trim()?.takeIf { it.isNotEmpty() && it.length <= 60 }
        ?: throw validationError("consignee is required (max 60 chars)", "consignee")
    val addr = req.address?.trim()?.takeIf { it.isNotEmpty() && it.length <= 255 }
        ?: throw validationError("address is required (max 255 chars)", "address")
    val ids = listOfNotNull(req.country_id, req.province_id, req.city_id, req.district_id)
    if (ids.any { it <= 0 }) throw validationError("region ids must be positive", "country_id")
    if (full && (req.country_id == null || req.province_id == null || req.city_id == null || req.district_id == null)) {
        throw validationError("country_id, province_id, city_id and district_id are all required", "country_id")
    }
    return AddressFields(
        consignee,
        req.country_id ?: 0L, req.province_id ?: 0L, req.city_id ?: 0L, req.district_id ?: 0L,
        addr, (req.zip ?: "").trim().take(20), (req.mobile ?: "").trim().take(32),
        req.is_default == true,
    )
}

private fun validateRegionChain(
    c: java.sql.Connection,
    countryId: Long?,
    provinceId: Long?,
    cityId: Long?,
    districtId: Long?,
) {
    fun level(id: Long?, parent: Long, type: Int, field: String) {
        if (id == null || id == 0L) {
            if (parent != 0L) throw validationError("$field is required to complete the region chain", field)
            return
        }
        val row = c.selectOne(
            "SELECT parent_id, region_type FROM ecs_region WHERE region_id = ?",
            listOf(id),
        ) { rs -> rs.long("parent_id") to rs.int("region_type") }
            ?: throw validationError("$field does not exist", field)
        if (row.second != type || (parent != 0L && row.first != parent)) {
            throw validationError("$field is not a valid child in the region chain", field)
        }
    }
    level(countryId, 0L, 1, "country_id")
    level(provinceId, countryId ?: 0L, 2, "province_id")
    level(cityId, provinceId ?: 0L, 3, "city_id")
    level(districtId, cityId ?: 0L, 4, "district_id")
}

private fun addressJson(rs: java.sql.ResultSet) = buildJsonObject {
    put("address_id", rs.long("address_id"))
    put("consignee", rs.str("consignee"))
    put("country_id", rs.long("country_id"))
    put("province_id", rs.long("province_id"))
    put("city_id", rs.long("city_id"))
    put("district_id", rs.long("district_id"))
    put("address", rs.str("address"))
    put("zipcode", rs.str("zipcode"))
    put("mobile", rs.str("mobile"))
    put("is_default", rs.int("is_default") == 1)
}
