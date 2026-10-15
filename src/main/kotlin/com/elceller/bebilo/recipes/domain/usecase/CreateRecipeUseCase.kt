package com.elceller.bebilo.recipes.domain.usecase

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

class CreateRecipeUseCase(
    private val auth: AuthUserRepository,
    private val contexts: CreationContextRepository,
    private val storage: suspend () -> RecipeWriteRepository,
    private val dispatcher: suspend () -> GenerationDispatcher,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend operator fun invoke(token: String, request: CreateRecipeInput): Recipe {
        val instant = clock.instant()
        val referenceDate = instant.atZone(ZoneId.of("Europe/Madrid")).toLocalDate()
        val uid = auth.getUserUid(token)
        validateRequest(request)
        val settings = contexts.settings(token).also { it.validate() }
        val available = contexts.babies(token).map { it.forGeneration(completedMonths(it.birthDate, referenceDate)) }
        if (available.map { it.id }.distinct().size != available.size || available.any {
            it.id.isBlank() || it.name.isBlank()
        }) throw DependencyResponseException()
        val babies = request.babyIds.map { id ->
            available.singleOrNull { it.id == id } ?: throw BabyNotFoundException()
        }
        val ingredients = request.ingredients
        val now = instant.toString()
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
        val context = GenerationContext(request, settings, babies, referenceDate.toString())
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

internal fun completedMonths(birthDate: String, referenceDate: LocalDate): Int {
    val date = runCatching { LocalDate.parse(birthDate) }.getOrNull()
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(birthDate) || date == null || date.toString() != birthDate || date > referenceDate) throw DependencyResponseException()
    return ChronoUnit.MONTHS.between(date, referenceDate).toInt()
}
