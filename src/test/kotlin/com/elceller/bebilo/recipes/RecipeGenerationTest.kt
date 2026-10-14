package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.request.CreateRecipeRequest
import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest
import java.util.concurrent.CopyOnWriteArrayList

class MemoryRecipes : RecipeWriteRepository, RecipeRepository {
    val values = linkedMapOf<Pair<String, String>, Recipe>()
    val contexts = linkedMapOf<Pair<String, String>, GenerationContext>()
    val history = CopyOnWriteArrayList<Recipe>()
    val images = mutableMapOf<Pair<String, String>, Map<String, String>>()
    var failCreate = false
    var failSave = false
    override suspend fun create(ownerUid: String, recipe: Recipe, context: GenerationContext): String {
        if (failCreate) throw ServiceUnavailableException()
        val id = "document-${values.size}"
        values[ownerUid to id] = recipe; contexts[ownerUid to id] = context; history.add(recipe)
        return id
    }
    override suspend fun save(ownerUid: String, documentId: String, recipe: Recipe, images: Map<String, String>) {
        if (failSave) throw ServiceUnavailableException()
        values[ownerUid to documentId] = recipe; this.images[ownerUid to documentId] = images; history.add(recipe)
    }
    override suspend fun getAll(ownerUid: String) = values.filterKeys { it.first == ownerUid }.values.toList()
    override suspend fun get(ownerUid: String, recipeId: String) = getAll(ownerUid).singleOrNull { it.summary.id == recipeId }

}

fun creationRequest() = CreateRecipeRequest(listOf("baby-1"),
    listOf(RecipeIngredientDto("Patata", emptyList(), RecipeIngredientCategoryDto.Vegetables)), listOf("steam"))
fun creationContexts(style: String = "steps", mode: String = "none") = object : CreationContextRepository {
    override suspend fun settings(token: String) = AiSettings(style, mode, "gpt-6-luna")
    override suspend fun babies(token: String) = listOf(GenerationBaby("baby-1", "Nil", 8, "Puré", "suave", listOf("sin leche")))
}
fun creationAuth() = object : AuthUserRepository {
    override suspend fun getUserUid(bearerToken: String): String = when (bearerToken) {
        "token" -> "uid"; "other" -> "other-uid"; else -> throw UnauthorizedException()
    }
}
fun complete(initial: Recipe): Recipe = initial.copy(summary = initial.summary.copy(title = "Patata al vapor"),
    ingredients = initial.ingredients.map { it.copy(quantity = 100.0, unit = "g") },
    instructions = when (initial.instructions) {
        is RecipeInstructions.Steps -> RecipeInstructions.Steps(listOf(RecipeStep("s1", "Cocer al vapor"), RecipeStep("s2", "Triturar")))
        is RecipeInstructions.Narrative -> RecipeInstructions.Narrative(listOf(RecipeParagraph("p1", "Cocer al vapor. Triturar.")))
    }, servings = initial.servings ?: 1).verifiedFor()

fun Recipe.verifiedFor(babies: List<GenerationBaby> = listOf(GenerationBaby("baby-1", "Nil", 8, "Puré", "suave", listOf("sin leche")))) =
    copy(safetyVerification = RecipeSafetyVerification(copy(safetyVerification = null), babies.map { it.copy(name = "") }, true))
fun Recipe.verifiedFor(context: GenerationContext) = verifiedFor(context.babies)

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeGenerationTest {
    @TestFactory fun `optional photo failures always preserve valid text and finish Ready`() =
        listOf("planning", "invalid-plan", "image", "upload").map { failure -> DynamicTest.dynamicTest(failure) { runTest {
            val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>(); val reports = mutableListOf<String>()
            CreateRecipeUseCase(creationAuth(), creationContexts(mode = "single"), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
            val generator = object : RecipeGenerator {
                override suspend fun compose(context: GenerationContext, initial: Recipe) = complete(initial)
                override suspend fun planImages(context: GenerationContext, recipe: Recipe): List<PlannedImage> {
                    if (failure == "planning") error("plan failed")
                    return if (failure == "invalid-plan") listOf(PlannedImage("missing", "Proceso", "Proceso"))
                        else listOf(PlannedImage(null, "Portada", "Plato"))
                }
            }
            val imageGenerator = object : RecipeImageGenerator { override suspend fun generate(prompt: String): ByteArray {
                if (failure == "image") error("image failed"); return byteArrayOf(1)
            } }
            val storage = object : RecipeImageStorage {
                override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray): StoredRecipeImage = error("storage unavailable")
            }
            val worker = RecipeGenerationService(repo, { generator }, { imageGenerator }, { storage },
                report = reports::add, scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
            worker.submit(jobs.single()); advanceUntilIdle()
            val value = repo.values.values.single()
            assertEquals(RecipeStatus.Ready, value.status); assertNull(value.summary.coverImage)
            assertTrue((value.instructions as RecipeInstructions.Steps).steps.isNotEmpty())
            assertTrue(repo.images.values.all { it.isEmpty() }); assertTrue(reports.isNotEmpty()); worker.close()
        } } }
    @TestFactory fun `POST and GET accept pending content in every representation`() = listOf("steps", "narrative").flatMap { style ->
        listOf("none", "single", "multiple").map { mode -> DynamicTest.dynamicTest("$style $mode") { testApplication {
            val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
            application { module {
                provide<AuthUserRepository> { creationAuth() }; provide<RecipeRepository> { repo }
                provide<RecipeWriteRepository> { repo }; provide<CreationContextRepository> { creationContexts(style, mode) }
                provide<GenerationDispatcher> { GenerationDispatcher { jobs.add(it) } }
            } }
            val response = client.post("/v1/recipe") { bearerAuth("token"); contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(creationRequest())) }
            assertEquals(HttpStatusCode.OK, response.status)
            val value = Json.decodeFromString<RecipeDto>(response.bodyAsText())
            assertEquals(RecipeStatusDto.Created, value.status)
            assertEquals("Receta en preparación", value.summary.title)
            assertEquals("Nil", value.summary.babies.single().name)
            assertEquals(1, jobs.size)
            assertNotEquals(value.summary.id, jobs.single().documentId)
            for (status in RecipeStatus.entries.filter { it != RecipeStatus.Ready }) {
                repo.save("uid", jobs.single().documentId, jobs.single().initial.copy(status = status))
                assertEquals(status.name, Json.decodeFromString<RecipeDto>(client.get("/v1/recipe/${value.summary.id}") {
                    bearerAuth("token") }.bodyAsText()).status.name)
                assertEquals(1, Json.decodeFromString<RecipesResponseDto>(client.get("/v1/recipes") {
                    bearerAuth("token") }.bodyAsText()).recipes.size)
            }
            assertEquals(HttpStatusCode.NotFound, client.get("/v1/recipe/${value.summary.id}") { bearerAuth("other") }.status)
            assertEquals(listOf("sin leche"), repo.contexts.values.single().babies.single().restrictions)
        } } }
    }
    @Test fun `invalid creation and dependencies never dispatch`() = runTest {
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
        val create = CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })
        for (input in listOf(creationRequest().copy(babyIds = emptyList()), creationRequest().copy(babyIds = listOf("baby-1", "baby-1")),
            creationRequest().copy(ingredients = emptyList()), creationRequest().copy(cookingMethods = emptyList()),
            creationRequest().copy(servings = 0), creationRequest().copy(title = " "),
            creationRequest().copy(ingredients = creationRequest().ingredients.map { it.copy(quantity = -1.0) }))) {
            assertThrows(ValidationException::class.java) { runBlocking { create("token", input.toDomain()) } }
        }
        assertThrows(BabyNotFoundException::class.java) { runBlocking { create("token", creationRequest().copy(babyIds = listOf("other")).toDomain()) } }
        assertThrows(UnauthorizedException::class.java) { runBlocking { create("invalid", creationRequest().toDomain()) } }
        repo.failCreate = true
        assertThrows(ServiceUnavailableException::class.java) { runBlocking { create("token", creationRequest().toDomain()) } }
        assertTrue(repo.values.isEmpty()); assertTrue(jobs.isEmpty())
    }
    @Test fun `two jobs execute while the third remains Created and cancelled HTTP does not cancel accepted work`() = runTest {
        val repo = MemoryRecipes(); val gate = CompletableDeferred<Unit>(); val started = mutableListOf<String>()
        val generator = object : RecipeGenerator {
            override suspend fun compose(context: GenerationContext, initial: Recipe): Recipe {
                started.add(initial.summary.id); gate.await(); return complete(initial)
            }
            override suspend fun planImages(context: GenerationContext, recipe: Recipe) = emptyList<PlannedImage>()
        }
        val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val worker = RecipeGenerationService(repo, { generator }, { error("images") }, { error("storage") }, scope = workerScope)
        val create = CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { worker })
        val requestJob = launch { repeat(3) { create("token", creationRequest().toDomain()) }; awaitCancellation() }
        runCurrent()
        assertEquals(2, started.size)
        assertEquals(1, repo.values.values.count { it.status == RecipeStatus.Created })
        requestJob.cancelAndJoin() // Worker lives in its own application scope.
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(3, repo.values.values.count { it.status == RecipeStatus.Ready })
        worker.close()
    }
    @Test fun `commit is dispatched even if HTTP is cancelled during the create write`() = runTest {
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val jobs = mutableListOf<GenerationJob>()
        val delegate = MemoryRecipes()
        val repo = object : RecipeWriteRepository by delegate {
            override suspend fun create(ownerUid: String, recipe: Recipe, context: GenerationContext): String {
                entered.complete(Unit); gate.await(); return delegate.create(ownerUid, recipe, context)
            }
        }
        val create = CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })
        val call = launch { create("token", creationRequest().toDomain()) }
        entered.await(); call.cancel(); gate.complete(Unit); call.join()
        assertEquals(1, jobs.size); assertEquals(1, delegate.values.size)
    }
    @TestFactory fun `composition and optional photos complete every style and photo mode`() = listOf("steps", "narrative").flatMap { style ->
        listOf("none", "single", "multiple").map { mode -> DynamicTest.dynamicTest("$style $mode") { runTest {
            val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>(); var imageCalls = 0
            CreateRecipeUseCase(creationAuth(), creationContexts(style, mode), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
            val generator = object : RecipeGenerator {
                override suspend fun compose(context: GenerationContext, initial: Recipe) = complete(initial)
                override suspend fun planImages(context: GenerationContext, recipe: Recipe) = listOf(PlannedImage(null, "Portada", "Plato")) +
                    if (mode == "multiple") listOf(PlannedImage(if (style == "steps") "s1" else "p1", "Proceso", "Proceso")) else emptyList()
            }
            val workerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val images = object : RecipeImageGenerator { override suspend fun generate(prompt: String): ByteArray {
                imageCalls++; if (prompt == "Proceso") error("failed image"); return byteArrayOf(1)
            } }
            val storage = object : RecipeImageStorage {
                override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray) = StoredRecipeImage("recipe-images/$ownerUid/$documentId/$imageId.jpg", "https://firebasestorage.googleapis.com/v0/b/demo/o/image?alt=media&token=test")
            }
            val actual = RecipeGenerationService(repo, { generator }, { images }, { storage }, scope = workerScope)
            actual.submit(jobs.single()); advanceUntilIdle()
            val result = repo.values.values.single()
            assertEquals(RecipeStatus.Ready, result.status)
            assertEquals(if (mode == "none") 0 else if (mode == "single") 1 else 2, imageCalls)
            assertEquals(mode != "none", result.summary.coverImage != null)
            assertEquals(listOf(RecipeStatus.Created, RecipeStatus.Loading), repo.history.take(2).map { it.status })
            result.validateStoredContent(); actual.close()
        } } }
    }
    @Test fun `failed composition does not fail sibling and Error persistence failure is reported`() = runTest {
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>(); val reports = mutableListOf<String>()
        val create = CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })
        create("token", creationRequest().toDomain()); create("other", creationRequest().toDomain())
        val agent = object : RecipeGenerator {
            override suspend fun compose(context: GenerationContext, initial: Recipe): Recipe {
                if (initial.summary.id == jobs.first().initial.summary.id) error("provider failed")
                return complete(initial)
            }
            override suspend fun planImages(context: GenerationContext, recipe: Recipe) = emptyList<PlannedImage>()
        }
        val worker = RecipeGenerationService(repo, { agent }, { error("unused") }, { error("unused") },
            report = reports::add, scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
        jobs.forEach(worker::submit); advanceUntilIdle()
        assertEquals(RecipeStatus.Error, repo.getAll("uid").single().status)
        assertEquals(RecipeStatus.Ready, repo.getAll("other-uid").single().status)
        repo.failSave = true; worker.submit(jobs.first()); advanceUntilIdle()
        assertTrue("RECIPE_ERROR_PERSISTENCE_FAILED" in reports); worker.close()
    }
    @Test fun `generator cannot modify selected ingredients methods identity or instruction style`() = runTest {
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
        CreateRecipeUseCase(creationAuth(), creationContexts(), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
        val job = jobs.single(); val value = complete(job.initial)
        for (invalid in listOf(value.copy(cookingMethods = listOf("fry")), value.copy(ingredients = emptyList()),
            value.copy(summary = value.summary.copy(babies = listOf(RecipeBaby("other", "Other")))),
            value.copy(instructions = RecipeInstructions.Narrative(listOf(RecipeParagraph("p", "Texto")))),
            value.copy(ingredients = value.ingredients.map { it.copy(category = RecipeIngredientCategory.Protein) }))) {
            assertThrows(Exception::class.java) { validateComposition(job, invalid) }
        }
        val extra = RecipeIngredient("Aceite", emptyList(), RecipeIngredientCategory.OilAndFats)
        validateComposition(job, value.copy(ingredients = value.ingredients + extra).verifiedFor(job.context))
        assertThrows(IllegalArgumentException::class.java) { validateImagePlan(value.copy(imageMode = RecipeImageMode.Contextual),
            listOf(PlannedImage(null, "Cover", "Cover"), PlannedImage("missing", "Other", "Other"))) }
    }
}
