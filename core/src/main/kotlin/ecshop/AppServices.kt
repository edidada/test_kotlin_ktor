package ecshop

import ecshop.db.Db

/** Dependencies handed to every route module. */
class AppServices(val db: Db, val config: AppConfig)
