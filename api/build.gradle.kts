plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    // All HTTP route modules live here and depend only on :core.
    api(project(":core"))
    implementation(libs.bundles.kotlinxEcosystem)
    implementation(libs.bundles.ktorServer)

    testImplementation(kotlin("test"))
}
