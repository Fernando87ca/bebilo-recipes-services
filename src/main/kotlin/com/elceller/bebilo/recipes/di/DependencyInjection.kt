package com.elceller.bebilo.recipes.di

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.config.AppConfigLoader
import com.elceller.bebilo.recipes.data.repository.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.*

fun Application.configureDependencyInjection(overrides: DependencyRegistry.() -> Unit = {}) {
    dependencies {
        overrides()
        provideDefault<AppConfig> { AppConfigLoader.load() }
        provideDefault<AuthUserRepository> { AuthServicesUserRepository(resolve<AppConfig>().authServiceBaseUrl) }
        provideDefault<RecipeRepository> { FirestoreRecipeRepository.fromConfig(resolve()) }
        // Resolve storage only after authentication, including invalid bearer tokens.
        provideDefault<GetRecipesUseCase> { GetRecipesUseCase(resolve()) { resolve<RecipeRepository>() } }
        provideDefault<GetRecipeUseCase> { GetRecipeUseCase(resolve()) { resolve<RecipeRepository>() } }
    }
}

private inline fun <reified T> DependencyRegistry.provideDefault(
    noinline dependency: suspend DependencyResolver.() -> T?,
) {
    if (DependencyKey<T>() !in this) provide<T>(provide = dependency)
}
