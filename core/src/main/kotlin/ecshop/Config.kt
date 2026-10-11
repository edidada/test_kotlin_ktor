package ecshop

import java.io.File

/**
 * Read-only application configuration sourced from environment variables.
 * Defaults keep local development (`curl http://localhost:8080`) working out of the box.
 */
data class AppConfig(
    val host: String,
    val port: Int,
    val dbPath: String,
    /** Root URL used to build absolute links in sitemap/feed output; never trust the Host header. */
    val baseUrl: String,
    /** HMAC secret for payment provider callbacks. Production must override it. */
    val paymentCallbackHmacSecret: String,
) {
    companion object {
        fun fromEnvironment(): AppConfig {
            val port = (System.getenv("EC_PORT") ?: "8080").toIntOrNull() ?: 8080
            return AppConfig(
                host = System.getenv("EC_HOST") ?: "0.0.0.0",
                port = port,
                dbPath = System.getenv("EC_DB_PATH") ?: "data${File.separatorChar}ecshop.db",
                baseUrl = (System.getenv("EC_BASE_URL") ?: "http://localhost:$port").trimEnd('/'),
                paymentCallbackHmacSecret =
                    System.getenv("EC_PAYMENT_CALLBACK_HMAC_SECRET") ?: "dev-payment-callback-secret",
            )
        }
    }
}
