package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.data.mapper.toRecipe
import com.elceller.bebilo.recipes.data.mapper.toStorageMap
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.RecipeRepository
import com.elceller.bebilo.recipes.domain.repository.RecipeStatusRepository
import com.elceller.bebilo.recipes.domain.repository.RecipeWriteRepository
import com.google.api.core.ApiFuture
import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.StatusCode.Code
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreException
import com.google.cloud.firestore.FirestoreOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileInputStream
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class FirestoreRecipeRepository(private val firestore: Firestore) : RecipeRepository, RecipeWriteRepository,
    RecipeStatusRepository, AutoCloseable {
    override suspend fun create(ownerUid: String, recipe: Recipe, context: GenerationContext): String = storageCall {
        recipe.validateStoredContent()
        val reference = collection(ownerUid).document()
        reference.create(
            recipe.toStorageMap() + mapOf(
                "generationContext" to context.toStorageMap(),
                "generationImages" to emptyMap<String, String>()
            )
        ).await()
        reference.id
    }

    override suspend fun save(ownerUid: String, documentId: String, recipe: Recipe, images: Map<String, String>) {
        storageCall {
            recipe.validateStoredContent()
            collection(ownerUid)
                .document(documentId)
                .update(recipe.toStorageMap() + ("generationImages" to images))
                .await()
        }
    }

    override suspend fun imagePath(ownerUid: String, recipeId: String, imageId: String): String? = storageCall {
        val documents = collection(ownerUid).whereEqualTo("summary.id", recipeId).get().await().documents
        if (documents.size > 1) throw InvalidStoredRecipeException()
        val document = documents.singleOrNull() ?: return@storageCall null
        val references = document.get("generationImages") as? Map<*, *> ?: return@storageCall null
        val path = references[imageId] as? String ?: return@storageCall null
        if (path != "recipe-images/$ownerUid/${document.id}/$imageId.jpg") throw InvalidStoredRecipeException()
        path
    }

    override suspend fun getAll(ownerUid: String): List<Recipe> = storageCall {
        val recipes = collection(ownerUid).get().await().documents.map { it.data.toRecipe() }
        if (recipes.map { it.summary.id }.distinct().size != recipes.size) throw InvalidStoredRecipeException()
        recipes
    }

    override suspend fun get(ownerUid: String, recipeId: String): Recipe? = storageCall {
        val documents = collection(ownerUid).whereEqualTo("summary.id", recipeId).get().await().documents
        if (documents.size > 1) throw InvalidStoredRecipeException()
        documents.singleOrNull()?.data?.toRecipe()?.also {
            if (it.summary.id != recipeId) throw InvalidStoredRecipeException()
        }
    }

    override fun observeStatuses(ownerUid: String): Flow<List<RecipeStatusChange>> = callbackFlow {
        val changes = RecipeStatusTracker()

        val listener = try {
            collection(ownerUid).addSnapshotListener(Executor { it.run() }) { snapshot, error ->
                if (error != null) {
                    close(storageFailure(error))
                } else {
                    try {
                        val batch = changes
                            .accept(
                                recipes = snapshot
                                    ?.documents
                                    ?.map { it.data.toRecipe() }
                                    ?: throw InternalServiceException()
                            )
                        if (batch != null && trySend(batch).isFailure) {
                            close(ServiceUnavailableException())
                        }
                    } catch (exception: Exception) {
                        close(storageFailure(exception))
                    }
                }
            }
        } catch (exception: Exception) {
            throw storageFailure(exception)
        }

        awaitClose { listener.remove() }
    }.buffer(64)

    private fun collection(uid: String) = firestore.collection("users").document(uid).collection("recipes")
    override fun close() = firestore.close()

    private suspend fun <T> storageCall(block: suspend () -> T): T = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        throw storageFailure(exception)
    }

    private fun storageFailure(exception: Exception): RecipeException = when (exception) {
        is RecipeException -> exception
        is FirestoreException -> when (exception.status?.code?.name) {
            "UNAVAILABLE", "DEADLINE_EXCEEDED", "RESOURCE_EXHAUSTED", "ABORTED" ->
                ServiceUnavailableException().also { it.initCause(exception) }

            else -> InternalServiceException().also { it.initCause(exception) }
        }

        is ApiException -> when (exception.statusCode.code) {
            Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED, Code.RESOURCE_EXHAUSTED, Code.ABORTED ->
                ServiceUnavailableException().also { it.initCause(exception) }

            else -> InternalServiceException().also { it.initCause(exception) }
        }

        else -> InternalServiceException().also { it.initCause(exception) }
    }

    companion object {
        fun fromConfig(config: AppConfig): FirestoreRecipeRepository {
            val projectId = config.firebaseProjectId?.takeIf(String::isNotBlank) ?: throw InternalServiceException()
            // Emulator tests never need production credentials.
            if (!System.getenv("FIRESTORE_EMULATOR_HOST").isNullOrBlank()) {
                return FirestoreRecipeRepository(FirestoreOptions.newBuilder().setProjectId(projectId).build().service)
            }
            return try {
                val credentials = config.googleApplicationCredentials?.let { path ->
                    FileInputStream(path).use(GoogleCredentials::fromStream)
                } ?: GoogleCredentials.getApplicationDefault()
                val options = FirebaseOptions.builder().setProjectId(projectId).setCredentials(credentials).build()
                val name = "bebilo-recipes-$projectId"
                val app = synchronized(FirestoreRecipeRepository::class.java) {
                    FirebaseApp.getApps().firstOrNull { it.name == name } ?: FirebaseApp.initializeApp(options, name)
                }
                FirestoreRecipeRepository(FirestoreClient.getFirestore(app))
            } catch (exception: Exception) {
                throw InternalServiceException().also { it.initCause(exception) }
            }
        }
    }
}

private suspend fun <T> ApiFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try {
            continuation.resume(get())
        } catch (exception: ExecutionException) {
            continuation.resumeWithException(exception.cause ?: exception)
        } catch (exception: Exception) {
            continuation.resumeWithException(exception)
        }
    }, Runnable::run)
    continuation.invokeOnCancellation { cancel(true) }
}
