import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("buildsrc.convention.kotlin-jvm")

    // Executable JVM application (bin scripts + lib/*.jar layout via installDist/distZip).
    application

    // Single self-contained fat jar (build/libs/app-all.jar) for simple `java -jar` deployment.
    id("com.gradleup.shadow") version "8.3.6"
}

dependencies {
    implementation(project(":api"))

    implementation(libs.ktorServerNetty)
    implementation(libs.logbackClassic)

    testImplementation(kotlin("test"))
}

application {
    // Kotlin compiles `App.kt` to a class with FQN `ecshop.AppKt`.
    mainClass = "ecshop.AppKt"
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier = "all"
    mergeServiceFiles()
    // Deterministic, embeddable-DB friendly: keep native sqlite jars usable from a fat jar.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") { dependsOn("shadowJar") }
