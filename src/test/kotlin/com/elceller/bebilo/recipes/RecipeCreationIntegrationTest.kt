package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import com.google.cloud.firestore.FirestoreOptions
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class RecipeCreationIntegrationTest {
    @Test fun `real Firestore preserves creation context through transitions and isolates image references`() = runBlocking<Unit> {
        val firestore = FirestoreOptions.newBuilder().setProjectId("demo-bebilo-recipes").build().service
        val uid = UUID.randomUUID().toString()
        val repository = FirestoreRecipeRepository(firestore)
        val jobs = mutableListOf<GenerationJob>()
        val auth = object : AuthUserRepository { override suspend fun getUserUid(bearerToken: String) = uid }
        try {
            val created = CreateRecipeUseCase(auth, creationContexts(), { repository }, { GenerationDispatcher { jobs.add(it) } })(
                "fixture-token", creationRequest().toDomain())
            val job = jobs.single()
            assertNotEquals(created.summary.id, job.documentId)
            assertEquals(created, repository.get(uid, created.summary.id))
            assertEquals(listOf(created), repository.getAll(uid))
            val reference = firestore.collection("users").document(uid).collection("recipes").document(job.documentId)
            val context = reference.get().get().get("generationContext")
            assertNotNull(context); assertFalse(context.toString().contains("fixture-token"))
            val path = "recipe-images/$uid/${job.documentId}/image-1.jpg"
            val images = mapOf("image-1" to path)
            for (status in listOf(RecipeStatus.Loading, RecipeStatus.Ready, RecipeStatus.Error)) {
                val result = if (status == RecipeStatus.Ready) complete(created).copy(status = status)
                    else created.copy(status = status)
                repository.save(uid, job.documentId, result, images)
                assertEquals(result, repository.get(uid, created.summary.id))
                assertEquals(context, reference.get().get().get("generationContext"))
            }
            assertEquals(path, repository.imagePath(uid, created.summary.id, "image-1"))
            assertNull(repository.imagePath("other-$uid", created.summary.id, "image-1"))
            assertNull(repository.imagePath(uid, created.summary.id, "unknown"))
            reference.update("generationImages", mapOf("image-1" to "profile-photos/other/private.jpg")).get()
            assertThrows(InvalidStoredRecipeException::class.java) { runBlocking { repository.imagePath(uid, created.summary.id, "image-1") } }
            assertThrows(InvalidStoredRecipeException::class.java) { runBlocking { repository.save(uid, job.documentId, created.copy(status = RecipeStatus.Ready)) } }
        } finally {
            jobs.forEach { firestore.collection("users").document(uid).collection("recipes").document(it.documentId).delete().get() }
            firestore.close()
        }
    }
}
