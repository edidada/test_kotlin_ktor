package ecshop.http

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.util.AttributeKey
import java.util.UUID

val RequestIdAttribute = AttributeKey<String>("ec.requestId")

/** Honours an inbound X-Request-Id or mints one; echoes it back for log correlation. */
fun Application.installRequestId() {
    intercept(ApplicationCallPipeline.Plugins) {
        val inbound = call.request.headers["X-Request-Id"]?.takeIf { it.isNotBlank() && it.length <= 128 }
        val id = inbound ?: UUID.randomUUID().toString()
        call.attributes.put(RequestIdAttribute, id)
        if (inbound == null) call.response.headers.append("X-Request-Id", id)
    }
}

fun ApplicationCall.requestId(): String = attributes.getOrNull(RequestIdAttribute) ?: ""
