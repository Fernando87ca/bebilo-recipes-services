package com.elceller.bebilo.recipes.domain.usecase

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.UUID

class RecipeGenerationService(
    private val storage: RecipeWriteRepository,
    private val generator: suspend () -> RecipeGenerator,
    private val imageGenerator: suspend () -> RecipeImageGenerator,
    private val imageStorage: suspend () -> RecipeImageStorage,
    concurrency: Int = 2,
    private val report: (String) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : GenerationDispatcher, AutoCloseable {

    private val permits = Semaphore(concurrency)

    override fun submit(job: GenerationJob) {
        check(scope.isActive) { "Generation service stopped" }
        scope.launch { permits.withPermit { generate(job) } }
    }

    private suspend fun generate(job: GenerationJob) {
        var current = job.initial
        val references = mutableMapOf<String, String>()
        suspend fun save() = storage.save(job.ownerUid, job.documentId, current, references.toMap())

        try {
            current = current.copy(status = RecipeStatus.Loading, updatedAt = Instant.now().toString())
            save()
            val agent = generator()
            val result = agent.compose(job.context, current)
            validateComposition(job, result)
            current = result.copy(status = RecipeStatus.Loading, updatedAt = Instant.now().toString())
            save()
            if (current.imageMode != RecipeImageMode.None) {
                val plan = try { agent.planImages(job.context, current).also { validateImagePlan(current, it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { report("IMAGE_PLAN_FAILED"); emptyList() }
                for (image in plan) {
                    val attached = try {
                        val photoStorage = imageStorage()
                        val bytes = imageGenerator().generate(image.prompt)
                        val id = UUID.randomUUID().toString()
                        val stored = photoStorage.upload(job.ownerUid, job.documentId, id, bytes)
                        Triple(id, stored.path, attachImage(current, image.instructionId, RecipeImage(stored.url, image.altText)))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { report("IMAGE_GENERATION_OR_UPLOAD_FAILED"); null }
                    if (attached != null) {
                        references[attached.first] = attached.second
                        current = attached.third.copy(updatedAt = Instant.now().toString())
                        save() // Database failures are recipe failures, rather than missing-image failures.
                    }
                }
            }
            current = current.copy(status = RecipeStatus.Ready, updatedAt = Instant.now().toString())
            current.validateStoredContent()
            save()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            report("RECIPE_GENERATION_FAILED")
            current = current.copy(status = RecipeStatus.Error, updatedAt = Instant.now().toString())
            try { save() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report("RECIPE_ERROR_PERSISTENCE_FAILED") }
        }
    }
    override fun close() { scope.cancel() }
}

fun validateComposition(job: GenerationJob, recipe: Recipe) {
    recipe.copy(status = RecipeStatus.Ready).validateStoredContent()
    require(recipe.summary.id == job.initial.summary.id && recipe.summary.babies == job.initial.summary.babies)
    require(recipe.imageMode == job.initial.imageMode && recipe.createdAt == job.initial.createdAt)
    require(recipe.summary.coverImage == null)
    val proof = requireNotNull(recipe.safetyVerification) { "Missing final safety verification" }
    require(proof.recipe == recipe.copy(safetyVerification = null))
    require(proof.babies == job.context.babies.map { it.copy(name = "") })
    require(job.context.request.title == null || recipe.summary.title == job.context.request.title ||
        (!proof.requestedTitleCompatible && recipe.ingredientChanges.isNotEmpty()))
    require(recipe.cookingMethods.toSet() == job.initial.cookingMethods.toSet())
    require((recipe.instructions is RecipeInstructions.Steps) == (job.context.settings.instructionStyle == "steps"))
    require(recipe.ingredients.none { it.category == RecipeIngredientCategory.Custom })
    val changes = recipe.ingredientChanges.associateBy { it.originalIngredientIndex }
    require(changes.size == recipe.ingredientChanges.size)
    require(changes.keys.all { it in job.initial.ingredients.indices })
    var cursor = 0
    job.initial.ingredients.forEachIndexed { index, original ->
        val change = changes[index]
        if (change != null) {
            require(change.originalIngredient == original && change.reason.isNotBlank())
            if (change.action == IngredientChangeAction.Replacement) {
                val replacement = requireNotNull(change.replacementIngredient)
                require(replacement.catalogId == null && recipe.ingredients.getOrNull(cursor++) == replacement)
            } else require(change.replacementIngredient == null)
        } else {
            validateMaintainedIngredient(original, recipe.ingredients.getOrNull(cursor++) ?: error("Missing ingredient"))
        }
    }
    require(recipe.ingredients.drop(cursor).all { it.catalogId == null })
    require(recipe.ingredientChanges.isEmpty() || !recipe.adaptationNotes.isNullOrBlank())
    require(job.initial.servings == null || recipe.servings == job.initial.servings)
    require(recipe.summary.allergens == ingredientAllergens(recipe.ingredients))
}

/** All maintained ingredients may gain allergens, but declarations and supplied culinary data survive. */
fun validateMaintainedIngredient(original: RecipeIngredient, generated: RecipeIngredient) {
    require(original.name == generated.name && original.catalogId == generated.catalogId)
    require(generated.allergens.containsAll(original.allergens))
    require(original.category == RecipeIngredientCategory.Custom || original.category == generated.category)
    require(generated.category != RecipeIngredientCategory.Custom)
    require(original.quantity == null || original.quantity == generated.quantity)
    require(original.unit == null || original.unit == generated.unit)
    require(original.preparation == null || original.preparation == generated.preparation)
}

fun validateImagePlan(recipe: Recipe, plan: List<PlannedImage>) {
    require(plan.count { it.instructionId != null } <= 4)
    require(plan.count { it.instructionId == null } <= 1)
    require(plan.map { it.instructionId }.distinct().size == plan.size)
    val ids = when (val instructions = recipe.instructions) {
        is RecipeInstructions.Steps -> instructions.steps.map { it.id }
        is RecipeInstructions.Narrative -> instructions.paragraphs.map { it.id }
    }
    require(plan.all { it.prompt.isNotBlank() && it.altText.isNotBlank() && (it.instructionId == null || it.instructionId in ids) })
    require(recipe.imageMode != RecipeImageMode.Cover || plan.all { it.instructionId == null })
    require(recipe.imageMode != RecipeImageMode.None || plan.isEmpty())
}

private fun attachImage(recipe: Recipe, target: String?, image: RecipeImage): Recipe = if (target == null)
    recipe.copy(summary = recipe.summary.copy(coverImage = image))
else recipe.copy(instructions = when (val instructions = recipe.instructions) {
    is RecipeInstructions.Steps -> instructions.copy(steps = instructions.steps.map { if (it.id == target) it.copy(image = image) else it })
    is RecipeInstructions.Narrative -> instructions.copy(paragraphs = instructions.paragraphs.map { if (it.id == target) it.copy(image = image) else it })
})
