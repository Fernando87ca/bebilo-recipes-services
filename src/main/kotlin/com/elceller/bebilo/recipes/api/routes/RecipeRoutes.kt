package com.elceller.bebilo.recipes.api.routes

import com.elceller.bebilo.recipes.api.response.RecipesResponseDto
import com.elceller.bebilo.recipes.data.mapper.toResponse
import com.elceller.bebilo.recipes.domain.model.UnauthorizedException
import com.elceller.bebilo.recipes.domain.usecase.GetRecipeUseCase
import com.elceller.bebilo.recipes.domain.usecase.GetRecipesUseCase
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.resolve
import io.ktor.server.response.respond
import io.ktor.server.routing.*

fun Route.recipeRoutes() {
    route("/v1") {
        get("/recipes") {
            val token = call.bearerToken()
            val useCase = call.application.dependencies.resolve<GetRecipesUseCase>()
            call.respond(RecipesResponseDto(useCase(token).map { it.toResponse() }))
        }
        get("/recipe/{recipeId}") {
            val token = call.bearerToken()
            val useCase = call.application.dependencies.resolve<GetRecipeUseCase>()
            // Ktor already decodes each path segment; a second decode changes opaque IDs.
            call.respond(useCase(token, call.pathParameters["recipeId"].orEmpty()).toResponse())
        }
    }
}

private fun ApplicationCall.bearerToken(): String {
    val header = request.headers[HttpHeaders.Authorization] ?: throw UnauthorizedException()
    val parts = header.trim().split(Regex("\\s+"), limit = 2)
    if (parts.size != 2 || !parts[0].equals("Bearer", true) || parts[1].isBlank()) throw UnauthorizedException()
    return parts[1]
}
