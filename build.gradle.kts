plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.elceller.bebilo-services"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.di)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.firebase.admin)
    implementation(libs.koog.agents)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(23)
}

application {
    mainClass.set("com.elceller.bebilo.recipes.MainKt")
}

tasks.test {
    useJUnitPlatform()
    exclude("**/*IntegrationTest*")
}

tasks.register<Test>("integrationTest") {
    description = "Tests with the real Firestore SDK against a local emulator only"
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    include("**/*IntegrationTest*")
    outputs.upToDateWhen { false }
    doFirst {
        val emulatorHost = System.getenv("FIRESTORE_EMULATOR_HOST")
        require(!emulatorHost.isNullOrBlank()) { "FIRESTORE_EMULATOR_HOST is required; these tests never use real Firebase" }
    }
}
