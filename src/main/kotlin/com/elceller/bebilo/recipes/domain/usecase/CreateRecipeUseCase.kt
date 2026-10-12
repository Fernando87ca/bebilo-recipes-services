package com.elceller.bebilo.recipes.domain.usecase

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

class CreateRecipeUseCase(
    private val auth: AuthUserRepository,
    private val contexts: CreationContextRepository,
    private val storage: suspend () -> RecipeWriteRepository,
    private val dispatcher: suspend () -> GenerationDispatcher,
) {
    suspend operator fun invoke(token: String, request: CreateRecipeInput): Recipe {
        val uid = auth.getUserUid(token)
        validateRequest(request)
        val settings = contexts.settings(token).also { it.validate() }
        val available = contexts.babies(token)
        if (available.map { it.id }.distinct().size != available.size || available.any {
            it.id.isBlank() || it.name.isBlank() || it.ageMonths < 0
        }) throw DependencyResponseException()
        val babies = request.babyIds.map { id -> available.singleOrNull { it.id == id } ?: throw BabyNotFoundException() }
        val ingredients = request.ingredients
        val now = Instant.now().toString()
        val recipe = Recipe(
            summary = RecipeSummary(UUID.randomUUID().toString(), request.title ?: "Receta en preparación",
                babies.map { RecipeBaby(it.id, it.name) }, ingredientAllergens(ingredients)),
            ingredients = ingredients, cookingMethods = request.cookingMethods,
            instructions = if (settings.instructionStyle == "steps") RecipeInstructions.Steps(emptyList())
                else RecipeInstructions.Narrative(emptyList()),
            imageMode = when (settings.photoMode) { "none" -> RecipeImageMode.None; "single" -> RecipeImageMode.Cover; else -> RecipeImageMode.Contextual },
            servings = request.servings, createdAt = now, updatedAt = now, status = RecipeStatus.Created,
        )
        try { recipe.validateStoredContent() } catch (_: InvalidStoredRecipeException) { throw ValidationException("Datos de receta inválidos") }
        val context = GenerationContext(request, settings, babies)
        val repository = storage()
        val worker = dispatcher() // Resolve all local prerequisites before committing a recipe.
        // Once creation begins, cancellation of the HTTP call cannot leave an accepted document undispatched.
        withContext(NonCancellable) {
            val documentId = repository.create(uid, recipe, context)
            worker.submit(GenerationJob(uid, documentId, recipe, context))
        }
        return recipe
    }
}

internal fun validateRequest(request: CreateRecipeInput) {
    if (request.babyIds.isEmpty() || request.babyIds.any { it.isBlank() } || request.babyIds.distinct().size != request.babyIds.size ||
        request.ingredients.isEmpty() || request.cookingMethods.isEmpty() || request.cookingMethods.any { it.isBlank() } ||
        request.cookingMethods.distinct().size != request.cookingMethods.size ||
        listOf(request.title, request.additionalInstructions).any { it != null && it.isBlank() } ||
        request.servings?.let { it <= 0 } == true) throw ValidationException("Solicitud de creación inválida")
}

fun ingredientAllergens(ingredients: List<RecipeIngredient>): List<RecipeAllergen> {
    val values = ingredients.flatMap { it.allergens }
    if (values.groupBy { it.code }.values.any { group -> group.map { it.name }.distinct().size != 1 })
        throw ValidationException("Declaración de alérgenos inconsistente")
    return values.distinctBy { it.code }
}
