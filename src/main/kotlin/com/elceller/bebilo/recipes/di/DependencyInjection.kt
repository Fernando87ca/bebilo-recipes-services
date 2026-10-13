package com.elceller.bebilo.recipes.di

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.config.AppConfigLoader
import com.elceller.bebilo.recipes.data.repository.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import com.elceller.bebilo.recipes.data.generation.*
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.log
import io.ktor.server.plugins.di.*

fun Application.configureDependencyInjection(overrides: DependencyRegistry.() -> Unit = {}) {
    val app = this
    val prompts = PromptResources() // Validate and load the packaged prompts on startup, without contacting OpenAI.
    dependencies {
        overrides()
        provideDefault<AppConfig> { AppConfigLoader.load() }
        provideDefault<AuthUserRepository> { AuthServicesUserRepository(resolve<AppConfig>().authServiceBaseUrl) }
        provideDefault<RecipeRepository> { FirestoreRecipeRepository.fromConfig(resolve()) }
        // Resolve storage only after authentication, including invalid bearer tokens.
        provideDefault<RecipeStatusRepository> { resolve<RecipeRepository>() as RecipeStatusRepository }
        provideDefault<ObserveRecipeStatusesUseCase> { ObserveRecipeStatusesUseCase(resolve()) { resolve<RecipeStatusRepository>() } }
        provideDefault<GetRecipesUseCase> { GetRecipesUseCase(resolve()) { resolve<RecipeRepository>() } }
        provideDefault<GetRecipeUseCase> { GetRecipeUseCase(resolve()) { resolve<RecipeRepository>() } }
        provideDefault<PromptResources> { prompts }
        provideDefault<OpenAISecret> { OpenAISecret(resolve<AppConfig>().openaiApiKeyFile, resolve<AppConfig>().openaiApiKey) }
        provideDefault<CreationContextRepository> {
            val config = resolve<AppConfig>()
            KtorCreationContextRepository(config.aiConfigurationBaseUrl, config.babiesBaseUrl)
        }
        provideDefault<RecipeWriteRepository> { resolve<RecipeRepository>() as RecipeWriteRepository }
        provideDefault<RecipeGenerator> {
            val config = resolve<AppConfig>(); val secret = resolve<OpenAISecret>()
            KoogRecipeGenerator(OpenAILLMClient(httpClient = ResponsesTransport(llmHttpClient(), config.openaiBaseUrl,
                secret::read) { input, cached -> app.log.info("LLM tokens: input={} cached={}", input, cached) }), resolve())
        }
        provideDefault<RecipeImageGenerator> {
            val config = resolve<AppConfig>(); val secret = resolve<OpenAISecret>()
            OpenAIImages(llmHttpClient(), config.openaiBaseUrl, secret::read, config.imageModel)
        }
        provideDefault<RecipeImageStorage> { FirebaseRecipeImageStorage.fromConfig(resolve()) }
        provideDefault<GenerationDispatcher> {
            val config = resolve<AppConfig>()
            RecipeGenerationService(resolve(), { resolve<RecipeGenerator>() }, { resolve<RecipeImageGenerator>() },
                { resolve<RecipeImageStorage>() }, config.generationConcurrency,
                report = { app.log.warn("Recipe worker: {}", it) }).also { worker ->
                    app.monitor.subscribe(ApplicationStopped) { worker.close() }
                }
        }
        provideDefault<CreateRecipeUseCase> {
            CreateRecipeUseCase(resolve(), resolve(), { resolve<RecipeWriteRepository>() }, { resolve<GenerationDispatcher>() })
        }

    }
}

private fun llmHttpClient() = HttpClient(CIO) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    install(HttpTimeout) { requestTimeoutMillis = 180000; connectTimeoutMillis = 10000; socketTimeoutMillis = 180000 }
}

private inline fun <reified T> DependencyRegistry.provideDefault(
    noinline dependency: suspend DependencyResolver.() -> T?,
) {
    if (DependencyKey<T>() !in this) provide<T>(provide = dependency)
}
