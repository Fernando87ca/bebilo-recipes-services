package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.RecipeImageStorage
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.NoCredentials
import com.google.cloud.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class FirebaseRecipeImageStorage(private val storage: Storage, private val bucket: String) : RecipeImageStorage {
    override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val path = "recipe-images/$ownerUid/$documentId/$imageId.jpg"
        storage.create(BlobInfo.newBuilder(BlobId.of(bucket, path)).setContentType("image/jpeg").build(), bytes,
            Storage.BlobTargetOption.doesNotExist())
        path
    }
    override suspend fun read(path: String): ByteArray = withContext(Dispatchers.IO) {
        try { storage.get(bucket, path)?.getContent() ?: throw RecipeNotFoundException() }
        catch (failure: RecipeException) { throw failure }
        catch (_: Exception) { throw ServiceUnavailableException() }
    }
    companion object {
        fun fromConfig(config: AppConfig): FirebaseRecipeImageStorage {
            val bucket = config.firebaseStorageBucket?.takeIf { it.isNotBlank() } ?: throw ServiceUnavailableException()
            return try {
                if (config.storageEmulatorHost != null) {
                    require(config.firebaseProjectId?.startsWith("demo-") == true && !System.getenv("FIRESTORE_EMULATOR_HOST").isNullOrBlank())
                    val options = StorageOptions.newBuilder().setProjectId(config.firebaseProjectId)
                        .setHost(config.storageEmulatorHost).setCredentials(NoCredentials.getInstance())
                        .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(1).build()).build()
                    return FirebaseRecipeImageStorage(options.service, bucket)
                }
                val credentials = config.googleApplicationCredentials?.let { path ->
                    FileInputStream(path).use(GoogleCredentials::fromStream)
                } ?: GoogleCredentials.getApplicationDefault()
                val options = StorageOptions.newBuilder().setProjectId(config.firebaseProjectId).setCredentials(credentials)
                    .setRetrySettings(StorageOptions.getDefaultRetrySettings().toBuilder().setMaxAttempts(1).build()).build()
                FirebaseRecipeImageStorage(options.service, bucket)
            } catch (_: Exception) { throw ServiceUnavailableException() }
        }
    }
}
