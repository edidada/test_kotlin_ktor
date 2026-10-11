package ecshop.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Contract error body: {"code","message","request_id","details"} — see docs/03_api_contract.md. */
@Serializable
data class ApiErrorBody(
    val code: String,
    val message: String,
    val request_id: String,
    val details: JsonElement? = null,
)

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
    val details: JsonElement? = null,
) : Exception(message)

private fun fieldDetails(field: String): JsonElement = buildJsonObject { put("field", field) }

fun validationError(message: String, field: String? = null): ApiException =
    ApiException(
        HttpStatusCode.BadRequest,
        "validation_error",
        message,
        field?.let { fieldDetails(it) },
    )

fun unauthorized(message: String = "invalid or missing session"): ApiException =
    ApiException(HttpStatusCode.Unauthorized, "unauthenticated", message)

fun forbidden(message: String = "forbidden"): ApiException =
    ApiException(HttpStatusCode.Forbidden, "forbidden", message)

fun notFound(message: String = "resource not found"): ApiException =
    ApiException(HttpStatusCode.NotFound, "not_found", message)

fun conflict(message: String = "conflict"): ApiException =
    ApiException(HttpStatusCode.Conflict, "conflict", message)

fun outOfStock(message: String = "out_of_stock"): ApiException =
    ApiException(HttpStatusCode.Conflict, "out_of_stock", message)

fun invalidState(message: String = "state does not allow this action"): ApiException =
    ApiException(HttpStatusCode.UnprocessableEntity, "invalid_state", message)

/** Maps thrown [ApiException]s to the fixed error JSON; anything else becomes a safe 500. */
fun io.ktor.server.application.Application.installApiErrors() {
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respond(
                cause.status,
                ApiErrorBody(cause.code, cause.message, call.requestId(), cause.details),
            )
        }
        exception<Throwable> { call, cause ->
            val decoding = cause is BadRequestException || cause is ContentTransformationException
            if (decoding) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ApiErrorBody("validation_error", "malformed JSON request body", call.requestId()),
                )
            } else {
                call.application.environment.log.error("unhandled error for ${call.request.path()}", cause)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ApiErrorBody("internal_error", "internal error", call.requestId()),
                )
            }
        }
    }
}
