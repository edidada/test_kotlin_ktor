package ecshop.http

import io.ktor.server.application.ApplicationCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Shared list/paging/query-parameter conventions from docs/02_url_api_list.md. */

data class Paging(val page: Long, val pageSize: Long) {
    val offset: Long get() = (page - 1) * pageSize
    val limit: Long get() = pageSize
}

fun ApplicationCall.parsePaging(defaultPageSize: Long = 20): Paging {
    val page = request.queryParameters["page"]?.let { raw ->
        raw.toLongOrNull() ?: throw validationError("page must be an integer", "page")
    } ?: 1L
    val pageSize = request.queryParameters["page_size"]?.let { raw ->
        raw.toLongOrNull() ?: throw validationError("page_size must be an integer", "page_size")
    } ?: defaultPageSize
    if (page < 1) throw validationError("page must be >= 1", "page")
    if (pageSize < 1 || pageSize > 100) throw validationError("page_size must be between 1 and 100", "page_size")
    return Paging(page, pageSize)
}

/** Every list response carries page/page_size/total/items. */
fun listEnvelope(page: Long, pageSize: Long, total: Long, items: JsonArray): JsonObject =
    buildJsonObject {
        put("page", page)
        put("page_size", pageSize)
        put("total", total)
        put("items", items)
    }

fun ApplicationCall.queryLong(name: String): Long? =
    request.queryParameters[name]?.let { raw ->
        raw.toLongOrNull() ?: throw validationError("$name must be an integer", name)
    }

fun ApplicationCall.queryString(name: String): String? =
    request.queryParameters[name]?.takeIf { it.isNotBlank() }

/** Sort keys are always whitelisted; never interpolate an unchecked string into ORDER BY. */
fun resolveSort(raw: String?, allowed: Map<String, String>, default: String, field: String = "sort"): String {
    if (raw.isNullOrBlank()) return default
    return allowed[raw] ?: throw validationError("$field must be one of: ${allowed.keys.joinToString(",")}", field)
}

fun resolveOrder(raw: String?, default: String = "desc"): String = when (raw?.lowercase()) {
    null, "" -> default
    "asc", "desc" -> raw.lowercase()
    else -> throw validationError("order must be asc or desc", "order")
}

/** LIKE wildcard escaping; pair with `ESCAPE '\'` in the SQL text. */
fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
