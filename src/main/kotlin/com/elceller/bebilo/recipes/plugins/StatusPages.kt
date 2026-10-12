package com.elceller.bebilo.recipes.plugins

import com.elceller.bebilo.recipes.api.response.ApiErrorResponse
import com.elceller.bebilo.recipes.domain.model.*
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<RecipeException> { call, cause ->
            val status = when (cause) {
                is ValidationException -> HttpStatusCode.BadRequest
                is UnauthorizedException -> HttpStatusCode.Unauthorized
                is ForbiddenException -> HttpStatusCode.Forbidden
                is RecipeNotFoundException -> HttpStatusCode.NotFound
                is ServiceUnavailableException -> HttpStatusCode.ServiceUnavailable
                is InvalidStoredRecipeException, is InternalServiceException -> HttpStatusCode.InternalServerError
            }
            call.respond(status, ApiErrorResponse(cause.code, cause.message))
        }
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, ApiErrorResponse("VALIDATION_ERROR", "Solicitud inválida"))
        }
        exception<Throwable> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Recipe request failed", cause)
            call.respond(HttpStatusCode.InternalServerError, ApiErrorResponse("INTERNAL_ERROR", "Error interno del servicio"))
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respond(status, ApiErrorResponse("NOT_FOUND", "Receta no encontrada"))
        }
    }
}
