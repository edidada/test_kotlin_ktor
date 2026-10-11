package ecshop

import ecshop.db.Db
import ecshop.http.installApiErrors
import ecshop.http.installRequestId
import ecshop.routes.adDetailRoutes
import ecshop.routes.activityRoutes
import ecshop.routes.articleCategoryRoutes
import ecshop.routes.articleDetailRoutes
import ecshop.routes.brandGoodsRoutes
import ecshop.routes.brandListRoutes
import ecshop.routes.catalogRoutes
import ecshop.routes.categoryGoodsRoutes
import ecshop.routes.compareRoutes
import ecshop.routes.cycleImageRoutes
import ecshop.routes.exchangeGoodsRoutes
import ecshop.routes.feedRoutes
import ecshop.routes.goodsDetailRoutes
import ecshop.routes.goodsGalleryRoutes
import ecshop.routes.goodsPriceQuoteRoutes
import ecshop.routes.goodsSearchRoutes
import ecshop.routes.goodsWidgetRoutes
import ecshop.routes.healthRoutes
import ecshop.routes.homeRoutes
import ecshop.routes.messageListRoutes
import ecshop.routes.packageRoutes
import ecshop.routes.promotionRoutes
import ecshop.routes.quotationRoutes
import ecshop.routes.regionRoutes
import ecshop.routes.shippingOptionsRoutes
import ecshop.routes.sitemapRoutes
import ecshop.routes.tagCloudRoutes
import ecshop.routes.topicRoutes
import ecshop.routes.voteCurrentRoutes
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
        // M4 document endpoints (not JSON, not under /api/v1).
        sitemapRoutes(svc)
        feedRoutes(svc)
        cycleImageRoutes(svc)
        goodsWidgetRoutes(svc)
        // Business API (docs/02_url_api_list.md).
        route("/api/v1") {
            homeRoutes(svc)
            catalogRoutes(svc)
            categoryGoodsRoutes(svc)
            goodsDetailRoutes(svc)
            goodsPriceQuoteRoutes(svc)
            goodsSearchRoutes(svc)
            brandListRoutes(svc)
            brandGoodsRoutes(svc)
            articleDetailRoutes(svc)
            articleCategoryRoutes(svc)
            regionRoutes(svc)
            compareRoutes(svc)
            activityRoutes(svc)
            packageRoutes(svc)
            topicRoutes(svc)
            voteCurrentRoutes(svc)
            exchangeGoodsRoutes(svc)
            shippingOptionsRoutes(svc)
            promotionRoutes(svc)
            messageListRoutes(svc)
            adDetailRoutes(svc)
            goodsGalleryRoutes(svc)
            tagCloudRoutes(svc)
            quotationRoutes(svc)
        }
    }
}
