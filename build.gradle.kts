plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.elceller.bebilo-services"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(23)
}

tasks.test {
    useJUnitPlatform()
}
