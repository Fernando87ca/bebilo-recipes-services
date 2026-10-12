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
    val aiConfigurationBaseUrl: String = "http://localhost:8085/v1",
    val babiesBaseUrl: String = "http://localhost:8082/v1",
    val publicBaseUrl: String = "http://api.bebilo.localhost:8088/recipes",
    val firebaseStorageBucket: String? = null,
    val openaiApiKeyFile: String? = null,
    val openaiApiKey: String? = null,
    val openaiBaseUrl: String = "https://api.openai.com/v1",
    val imageModel: String = "gpt-image-2.5-flare",
    val generationConcurrency: Int = 2,
    val storageEmulatorHost: String? = null,
) {
    override fun toString(): String = "AppConfig(applicationEnv=$applicationEnv, port=$port)"
}

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
            aiConfigurationBaseUrl = (value("AI_CONFIGURATION_SERVICE_BASE_URL") ?: "http://localhost:8085/v1").trimEnd('/'),
            babiesBaseUrl = (value("BABY_SERVICE_BASE_URL") ?: "http://localhost:8082/v1").trimEnd('/'),
            publicBaseUrl = (value("RECIPES_PUBLIC_BASE_URL") ?: "http://api.bebilo.localhost:8088/recipes").trimEnd('/'),
            firebaseStorageBucket = value("FIREBASE_STORAGE_BUCKET"),
            openaiApiKeyFile = value("OPENAI_API_KEY_FILE"),
            openaiApiKey = value("OPENAI_API_KEY"),
            openaiBaseUrl = value("OPENAI_BASE_URL") ?: "https://api.openai.com/v1",
            imageModel = value("OPENAI_IMAGE_MODEL") ?: "gpt-image-2.5-flare",
            generationConcurrency = (value("RECIPE_GENERATION_CONCURRENCY") ?: "2").toInt().also { require(it > 0) },
            storageEmulatorHost = value("STORAGE_EMULATOR_HOST"),
        )
    }
}
