package com.elceller.bebilo.recipes.domain.model

sealed class RecipeException(val code: String, override val message: String) : RuntimeException(message)
class ValidationException(message: String = "Identificador de receta inválido") : RecipeException("VALIDATION_ERROR", message)
class UnauthorizedException : RecipeException("UNAUTHORIZED", "Se requiere un token Bearer válido")
class ForbiddenException : RecipeException("FORBIDDEN", "Acceso al servicio no permitido")
class RecipeNotFoundException : RecipeException("NOT_FOUND", "Receta no encontrada")
class InvalidStoredRecipeException : RecipeException("INTERNAL_ERROR", "Contenido de recetas inválido")
class ServiceUnavailableException : RecipeException("SERVICE_UNAVAILABLE", "Servicio temporalmente no disponible")
class InternalServiceException : RecipeException("INTERNAL_ERROR", "Error interno del servicio")

class BabyNotFoundException : RecipeException("BABY_NOT_FOUND", "Bebé no disponible para esta cuenta")
class DependencyResponseException : RecipeException("DEPENDENCY_ERROR", "Respuesta inválida de un servicio dependiente")
