package com.elceller.bebilo.recipes.plugins

import com.elceller.bebilo.recipes.api.routes.recipeRoutes
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        get("/health") { call.respondText("OK") }
        recipeRoutes()
    }
}
