package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.api.response.*
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.data.repository.FirebaseDownloadUrls
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.server.plugins.di.provide
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RecipeOptionalImagesTest {
    @TestFactory fun `image budget allows four instructions plus cover and rejects excess before generation`() =
        listOf("steps", "narrative").flatMap { style ->
            listOf(false, true).flatMap { cover ->
                listOf(4, 5).map { count -> DynamicTest.dynamicTest("$style cover=$cover instructions=$count") { runTest {
                    val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>(); val reports = mutableListOf<String>()
                    var generated = 0; var uploaded = 0
                    CreateRecipeUseCase(creationAuth(), creationContexts(style, "multiple"), { repo },
                        { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
                    val agent = object : RecipeGenerator {
                        override suspend fun compose(context: GenerationContext, initial: Recipe) = complete(initial).copy(
                            instructions = if (style == "steps") RecipeInstructions.Steps((1..6).map { RecipeStep("i$it", "Proceso $it") })
                            else RecipeInstructions.Narrative((1..6).map { RecipeParagraph("i$it", "Proceso $it") })).verifiedFor(context)
                        override suspend fun planImages(context: GenerationContext, recipe: Recipe) =
                            (if (cover) listOf(PlannedImage(null, "Portada", "Plato")) else emptyList()) +
                                (1..count).map { PlannedImage("i$it", "Proceso $it", "Proceso $it") }
                    }
                    val images = object : RecipeImageGenerator {
                        override suspend fun generate(prompt: String): ByteArray { generated++; return byteArrayOf(1) }
                    }
                    val photos = object : RecipeImageStorage {
                        override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray): StoredRecipeImage {
                            uploaded++
                            val path = "recipe-images/$ownerUid/$documentId/$imageId.jpg"
                            return StoredRecipeImage(path, FirebaseDownloadUrls.url("demo", path, "download-token"))
                        }
                    }
                    val worker = RecipeGenerationService(repo, { agent }, { images }, { photos }, report = reports::add,
                        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
                    try {
                        worker.submit(jobs.single()); advanceUntilIdle()
                        val ready = repo.values.values.single()
                        assertEquals(RecipeStatus.Ready, ready.status)
                        val expected = if (count == 4) count + (if (cover) 1 else 0) else 0
                        assertEquals(expected, generated); assertEquals(expected, uploaded)
                        assertEquals(expected, repo.images.values.single().size)
                        assertEquals(count == 4 && cover, ready.summary.coverImage != null)
                        val instructionImages = when (val instructions = ready.instructions) {
                            is RecipeInstructions.Steps -> instructions.steps.map { it.image }
                            is RecipeInstructions.Narrative -> instructions.paragraphs.map { it.image }
                        }
                        assertEquals(if (count == 4) 4 else 0, instructionImages.count { it != null })
                        assertEquals(if (count == 4) emptyList<String>() else listOf("IMAGE_PLAN_FAILED"), reports)
                    } finally { worker.close() }
                } } }
            }
        }

    @TestFactory fun `empty and process-only plans reach Ready and GET exposes optional images`() = listOf("steps", "narrative").flatMap { style ->
        listOf("single" to false, "multiple" to false, "multiple" to true).map { (mode, process) ->
            DynamicTest.dynamicTest("$style $mode process=$process") { runTest {
                val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>(); val reports = mutableListOf<String>()
                CreateRecipeUseCase(creationAuth(), creationContexts(style, mode), { repo }, { GenerationDispatcher { jobs.add(it) } })("token", creationRequest().toDomain())
                val agent = object : RecipeGenerator {
                    override suspend fun compose(context: GenerationContext, initial: Recipe) = complete(initial).let { value ->
                        if (style == "narrative") value.copy(instructions = RecipeInstructions.Narrative(listOf(
                            RecipeParagraph("p1", "Cocer al vapor."), RecipeParagraph("p2", "Servir.")))) else value
                    }.verifiedFor(context)
                    override suspend fun planImages(context: GenerationContext, recipe: Recipe) = if (process)
                        listOf(PlannedImage(if (style == "steps") "s1" else "p1", "Proceso", "Proceso")) else emptyList()
                }
                val photos = object : RecipeImageStorage {
                    override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray): StoredRecipeImage {
                        val path = "recipe-images/$ownerUid/$documentId/$imageId.jpg"
                        return StoredRecipeImage(path, FirebaseDownloadUrls.url("demo", path, "download-token"))
                    }
                }
                val worker = RecipeGenerationService(repo, { agent }, { object : RecipeImageGenerator {
                    override suspend fun generate(prompt: String) = byteArrayOf(1)
                } }, { photos }, report = reports::add, scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))
                try {
                    worker.submit(jobs.single()); advanceUntilIdle()
                    val ready = repo.values.values.single()
                    assertEquals(RecipeStatus.Ready, ready.status); assertNull(ready.summary.coverImage); assertTrue(reports.isEmpty())
                    val images = when (val instructions = ready.instructions) {
                        is RecipeInstructions.Steps -> instructions.steps.map { it.image }
                        is RecipeInstructions.Narrative -> instructions.paragraphs.map { it.image }
                    }
                    assertEquals(process, images.first() != null); assertNull(images.last())
                    testApplication {
                        application { module { provide<AuthUserRepository> { creationAuth() }; provide<RecipeRepository> { repo } } }
                        val detail = client.get("/v1/recipe/${ready.summary.id}") { bearerAuth("token") }.bodyAsText()
                        val listing = client.get("/v1/recipes") { bearerAuth("token") }.bodyAsText()
                        assertEquals(ready.toResponse(), Json.decodeFromString<RecipeDto>(detail))
                        assertEquals(ready.toResponse(), Json.decodeFromString<RecipesResponseDto>(listing).recipes.single())
                        assertFalse(detail.contains("/v1/recipe/"))
                        if (process) assertTrue(detail.contains("token=download-token"))
                    }
                } finally { worker.close() }
            } }
        }
    }
}
