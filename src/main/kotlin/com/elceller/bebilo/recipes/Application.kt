package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.di.configureDependencyInjection
import com.elceller.bebilo.recipes.plugins.configureRouting
import com.elceller.bebilo.recipes.plugins.configureSerialization
import com.elceller.bebilo.recipes.plugins.configureStatusPages
import com.elceller.bebilo.recipes.plugins.configureSwagger
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.DependencyRegistry

fun Application.module(overrides: DependencyRegistry.() -> Unit = {}) {
    configureSerialization()
    configureStatusPages()
    configureDependencyInjection(overrides)
    configureSwagger()
    configureRouting()
}
