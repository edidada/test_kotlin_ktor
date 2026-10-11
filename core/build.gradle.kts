plugins {
    id("buildsrc.convention.kotlin-jvm")
}

dependencies {
    // Shared platform code: persistence (SQLite), HTTP plumbing, money, config.
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.ktorServerCore)
    implementation(libs.ktorServerStatusPages)
    // Route modules catch org.sqlite.SQLiteException directly, so this type is part of :core's API.
    api(libs.sqliteJdbc)

    testImplementation(kotlin("test"))
}
