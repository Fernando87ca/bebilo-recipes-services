package com.elceller.bebilo.recipes.data.repository

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** These tokens grant download access independently of the caller's Firebase identity. */
object FirebaseDownloadUrls {
    const val TOKEN_METADATA = "firebaseStorageDownloadTokens"

    fun token(metadata: Map<String, String?>?): String? = metadata?.get(TOKEN_METADATA)
        ?.split(',')?.firstOrNull { it.isNotBlank() }?.trim()

    fun ensureUrl(storage: com.google.cloud.storage.Storage, blob: com.google.cloud.storage.Blob): String {
        val token = token(blob.metadata) ?: java.util.UUID.randomUUID().toString().also { token ->
            storage.update(blob.toBuilder().setMetadata((blob.metadata ?: emptyMap()) + (TOKEN_METADATA to token)).build(),
                com.google.cloud.storage.Storage.BlobTargetOption.metagenerationMatch())
        }
        return url(blob.bucket, blob.name, token)
    }

    fun url(bucket: String, path: String, token: String): String {
        require(bucket.isNotBlank() && path.isNotBlank() && token.isNotBlank())
        fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
        return "https://firebasestorage.googleapis.com/v0/b/${encode(bucket)}/o/${encode(path)}?alt=media&token=${encode(token)}"
    }
}
