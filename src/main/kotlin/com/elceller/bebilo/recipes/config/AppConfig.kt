package com.elceller.bebilo.recipes.config

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

data class AppConfig(
    val applicationEnv: String,
    val port: Int,
    val authServiceBaseUrl: String,
    val firebaseProjectId: String?,
    val googleApplicationCredentials: String?,
)

object AppConfigLoader {
    fun load(): AppConfig {
        val properties = Properties().also { values ->
            Path.of("local.properties").takeIf(Files::exists)?.let { Files.newInputStream(it).use(values::load) }
        }
        fun value(key: String) = System.getenv(key)?.takeIf(String::isNotBlank)
            ?: properties.getProperty(key)?.takeIf(String::isNotBlank)
        return AppConfig(
            applicationEnv = value("APPLICATION_ENV") ?: "local",
            port = value("PORT")?.toIntOrNull() ?: 8080,
            authServiceBaseUrl = value("AUTH_SERVICE_BASE_URL") ?: "http://localhost:8081/v1",
            firebaseProjectId = value("FIREBASE_PROJECT_ID"),
            googleApplicationCredentials = value("GOOGLE_APPLICATION_CREDENTIALS"),
        )
    }
}
