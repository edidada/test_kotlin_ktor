package ecshop

import ecshop.db.Db
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val cfg = AppConfig.fromEnvironment()
    val db = Db(cfg.dbPath)
    db.initializeSchema()
    val services = AppServices(db, cfg)
    embeddedServer(Netty, port = cfg.port, host = cfg.host) {
        appModule(services)
    }.start(wait = true)
}
