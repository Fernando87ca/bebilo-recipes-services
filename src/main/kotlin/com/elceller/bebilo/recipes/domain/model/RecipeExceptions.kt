package com.elceller.bebilo.recipes.domain.model

sealed class RecipeException(val code: String, override val message: String) : RuntimeException(message)
class ValidationException : RecipeException("VALIDATION_ERROR", "Identificador de receta inválido")
class UnauthorizedException : RecipeException("UNAUTHORIZED", "Se requiere un token Bearer válido")
class ForbiddenException : RecipeException("FORBIDDEN", "Acceso al servicio no permitido")
class RecipeNotFoundException : RecipeException("NOT_FOUND", "Receta no encontrada")
class InvalidStoredRecipeException : RecipeException("INTERNAL_ERROR", "Contenido de recetas inválido")
class ServiceUnavailableException : RecipeException("SERVICE_UNAVAILABLE", "Servicio temporalmente no disponible")
class InternalServiceException : RecipeException("INTERNAL_ERROR", "Error interno del servicio")
