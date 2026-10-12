package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.data.mapper.toRecipe
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.RecipeRepository
import com.google.api.core.ApiFuture
import com.google.api.gax.rpc.ApiException
import com.google.api.gax.rpc.StatusCode.Code
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.FirestoreOptions
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import java.io.FileInputStream
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

class FirestoreRecipeRepository(private val firestore: Firestore) : RecipeRepository, AutoCloseable {
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
    private fun collection(uid: String) = firestore.collection("users").document(uid).collection("recipes")
    override fun close() = firestore.close()

    private suspend fun <T> storageCall(block: suspend () -> T): T = try {
        block()
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: RecipeException) {
        throw exception
    } catch (exception: ApiException) {
        when (exception.statusCode.code) {
            Code.UNAVAILABLE, Code.DEADLINE_EXCEEDED, Code.RESOURCE_EXHAUSTED, Code.ABORTED ->
                throw ServiceUnavailableException().also { it.initCause(exception) }
            else -> throw InternalServiceException().also { it.initCause(exception) }
        }
    } catch (exception: Exception) {
        throw InternalServiceException().also { it.initCause(exception) }
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
