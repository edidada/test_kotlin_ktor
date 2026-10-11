package ecshop

import ecshop.http.installApiErrors
import ecshop.http.installRequestId
import ecshop.routes.accountRequestRoutes
import ecshop.routes.activityRoutes
import ecshop.routes.adClickRoutes
import ecshop.routes.addressRoutes
import ecshop.routes.adDetailRoutes
import ecshop.routes.affiliateRoutes
import ecshop.routes.articleCategoryRoutes
import ecshop.routes.articleDetailRoutes
import ecshop.routes.availabilityRoutes
import ecshop.routes.bonusRoutes
import ecshop.routes.bookingRoutes
import ecshop.routes.brandGoodsRoutes
import ecshop.routes.brandListRoutes
import ecshop.routes.catalogRoutes
import ecshop.routes.cartRoutes
import ecshop.routes.categoryGoodsRoutes
import ecshop.routes.checkoutRoutes
import ecshop.routes.commentsRoutes
import ecshop.routes.compareRoutes
import ecshop.routes.createOrderRoutes
import ecshop.routes.cycleImageRoutes
import ecshop.routes.emailVerificationRoutes
import ecshop.routes.exchangeGoodsRoutes
import ecshop.routes.favoritesRoutes
import ecshop.routes.feedRoutes
import ecshop.routes.goodsDetailRoutes
import ecshop.routes.goodsGalleryRoutes
import ecshop.routes.goodsPriceQuoteRoutes
import ecshop.routes.goodsSearchRoutes
import ecshop.routes.goodsWidgetRoutes
import ecshop.routes.groupBuyRoutes
import ecshop.routes.healthRoutes
import ecshop.routes.homeRoutes
import ecshop.routes.loginRoutes
import ecshop.routes.messageListRoutes
import ecshop.routes.messageWriteRoutes
import ecshop.routes.myCommentRoutes
import ecshop.routes.newsletterRoutes
import ecshop.routes.orderActionRoutes
import ecshop.routes.orderPatchRoutes
import ecshop.routes.orderQueryRoutes
import ecshop.routes.packageRoutes
import ecshop.routes.passwordResetRoutes
import ecshop.routes.paymentCallbackRoutes
import ecshop.routes.passwordRoutes
import ecshop.routes.profileRoutes
import ecshop.routes.promotionRoutes
import ecshop.routes.quotationRoutes
import ecshop.routes.registerRoutes
import ecshop.routes.regionRoutes
import ecshop.routes.shippingOptionsRoutes
import ecshop.routes.sitemapRoutes
import ecshop.routes.tagCloudRoutes
import ecshop.routes.tagWriteRoutes
import ecshop.routes.topicRoutes
import ecshop.routes.voteCurrentRoutes
import ecshop.routes.voteResponseRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

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
            registerRoutes(svc)
            availabilityRoutes(svc)
            loginRoutes(svc)
            profileRoutes(svc)
            passwordRoutes(svc)
            bonusRoutes(svc)
            emailVerificationRoutes(svc)
            passwordResetRoutes(svc)
            accountRequestRoutes(svc)
            addressRoutes(svc)
            cartRoutes(svc)
            checkoutRoutes(svc)
            createOrderRoutes(svc)
            orderQueryRoutes(svc)
            orderActionRoutes(svc)
            orderPatchRoutes(svc)
            paymentCallbackRoutes(svc)
            voteResponseRoutes(svc)
            commentsRoutes(svc)
            favoritesRoutes(svc)
            tagWriteRoutes(svc)
            bookingRoutes(svc)
            myCommentRoutes(svc)
            messageWriteRoutes(svc)
            adClickRoutes(svc)
            groupBuyRoutes(svc)
            affiliateRoutes(svc)
            newsletterRoutes(svc)
        }
    }
}
