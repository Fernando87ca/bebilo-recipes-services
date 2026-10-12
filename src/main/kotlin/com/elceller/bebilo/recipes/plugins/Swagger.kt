package com.elceller.bebilo.recipes.plugins

import io.ktor.server.application.Application
import io.ktor.http.ContentType
import io.ktor.server.plugins.swagger.swaggerUI
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

fun Application.configureSwagger() {
    val spec = checkNotNull(Thread.currentThread().contextClassLoader.getResource("openapi.yaml")) {
        "OpenAPI resource 'openapi.yaml' was not found"
    }.readText()
    routing {
        get("openapi.yaml") { call.respondText(spec, ContentType.parse("application/yaml")) }
        swaggerUI(path = "swagger", swaggerFile = "openapi.yaml")
    }
}
