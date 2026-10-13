package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.config.AppConfig
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.RecipeImageStorage
import com.elceller.bebilo.recipes.domain.repository.StoredRecipeImage
import java.util.UUID
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.NoCredentials
import com.google.cloud.storage.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

class FirebaseRecipeImageStorage(internal val storage: Storage, internal val bucket: String) : RecipeImageStorage {
    override suspend fun upload(ownerUid: String, documentId: String, imageId: String, bytes: ByteArray): StoredRecipeImage = withContext(Dispatchers.IO) {
        val path = "recipe-images/$ownerUid/$documentId/$imageId.jpg"
        val token = UUID.randomUUID().toString()
        storage.create(BlobInfo.newBuilder(BlobId.of(bucket, path)).setContentType("image/jpeg")
            .setMetadata(mapOf(FirebaseDownloadUrls.TOKEN_METADATA to token)).build(), bytes,
            Storage.BlobTargetOption.doesNotExist())
        StoredRecipeImage(path, FirebaseDownloadUrls.url(bucket, path, token))
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
