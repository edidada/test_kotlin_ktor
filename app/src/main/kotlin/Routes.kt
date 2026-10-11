package ecshop

import ecshop.db.Db
import ecshop.http.installApiErrors
import ecshop.http.installRequestId
import ecshop.routes.catalogRoutes
import ecshop.routes.healthRoutes
import ecshop.routes.homeRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

/** Dependencies handed to every route module. */
class AppServices(val db: Db, val config: AppConfig)

fun Application.appModule(svc: AppServices) {
    install(DefaultHeaders) { header("X-Engine", "kotlin-ktor-ecshop") }
    install(CallLogging)
    installRequestId()
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = true
            },
        )
    }
    installApiErrors()
    routing {
        // M0 health check.
        healthRoutes(svc)
        // Business API (docs/02_url_api_list.md).
        route("/api/v1") {
            homeRoutes(svc)
            catalogRoutes(svc)
        }
    }
}
