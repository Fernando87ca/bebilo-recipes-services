package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.repository.*
import com.elceller.bebilo.recipes.data.migration.LegacyImageMigration
import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.google.cloud.storage.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.DynamicTest

class FirebaseDownloadUrlsTest {
    @Test fun `upload creates private JPEG with encoded permanent download token`() = runBlocking {
        val storage = mockk<Storage>()
        val info = slot<BlobInfo>()
        every { storage.create(capture(info), any<ByteArray>(), *anyVararg()) } returns mockk<Blob>()
        val result = FirebaseRecipeImageStorage(storage, "demo-bucket").upload("niño +", "doc?#", "image", byteArrayOf(1))
        val token = info.captured.metadata!!.getValue(FirebaseDownloadUrls.TOKEN_METADATA)!!
        assertEquals("image/jpeg", info.captured.contentType)
        assertEquals("recipe-images/niño +/doc?#/image.jpg", result.path)
        assertEquals("https://firebasestorage.googleapis.com/v0/b/demo-bucket/o/recipe-images%2Fni%C3%B1o%20%2B%2Fdoc%3F%23%2Fimage.jpg?alt=media&token=$token", result.url)
        assertNull(info.captured.acl)
    }

    @Test fun `migration preserves existing tokens and unrelated metadata and can be repeated`() {
        val storage = mockk<Storage>()
        val blob = mockk<Blob>()
        every { blob.bucket } returns "demo-bucket"; every { blob.name } returns "recipe-images/uid/doc/img.jpg"
        every { blob.metadata } returns mapOf("other" to "keep", FirebaseDownloadUrls.TOKEN_METADATA to "existing,second")
        val first = FirebaseDownloadUrls.ensureUrl(storage, blob)
        assertTrue(first.endsWith("token=existing"))
        assertEquals(first, FirebaseDownloadUrls.ensureUrl(storage, blob))
        verify { storage wasNot Called }

        every { blob.metadata } returns mapOf("other" to "keep")
        val builder = mockk<Blob.Builder>()
        val metadata = slot<Map<String, String?>>()
        val updated = mockk<Blob>()
        every { blob.toBuilder() } returns builder
        every { builder.setMetadata(capture(metadata)) } returns builder
        every { builder.build() } returns updated
        val changed = slot<BlobInfo>()
        every { storage.update(capture(changed), *anyVararg<Storage.BlobTargetOption>()) } returns mockk<Blob>()
        every { updated.metadata } answers { metadata.captured }
        val created = FirebaseDownloadUrls.ensureUrl(storage, blob)
        assertEquals("keep", changed.captured.metadata!!["other"])
        every { blob.metadata } returns changed.captured.metadata
        assertEquals(created, FirebaseDownloadUrls.ensureUrl(storage, blob))
        verify(exactly = 1) { storage.update(any<BlobInfo>(), *anyVararg<Storage.BlobTargetOption>()) }
    }

    @TestFactory fun `legacy migration validates ownership and preserves both instruction formats`() = listOf("steps", "narrative").map { style ->
        DynamicTest.dynamicTest(style) {
            val base = "https://api.example/recipes"
            val old = "$base/v1/recipe/recipe-001/images/img"
            val value = storedRecipe(type = style).toRecipe().let { recipe ->
                LegacyImageMigration.replace(recipe, mapOf("https://example.com/cover.png" to old,
                    "https://example.com/step.png" to old, "https://example.com/paragraph.png" to old))
            }
            val references = mapOf("img" to "recipe-images/uid/doc/img.jpg")
            val targets = LegacyImageMigration.targets(value, "uid", "doc", references, base)
            assertEquals(listOf(LegacyImageMigration.Target(old, references.getValue("img"))), targets)
            val direct = FirebaseDownloadUrls.url("bucket", targets.single().path, "existing")
            val migrated = LegacyImageMigration.replace(value, mapOf(old to direct))
            assertEquals(value.summary.coverImage!!.altText, migrated.summary.coverImage!!.altText)
            assertEquals(value.instructions, LegacyImageMigration.replace(migrated, mapOf(direct to old)).instructions)
            assertTrue(LegacyImageMigration.targets(migrated, "uid", "doc", references, base).isEmpty())
            assertEquals(migrated, LegacyImageMigration.replace(migrated, mapOf(old to direct)))
            assertThrows(IllegalArgumentException::class.java) { LegacyImageMigration.targets(value, "other", "doc", references, base) }
            assertThrows(IllegalArgumentException::class.java) { LegacyImageMigration.targets(value, "uid", "doc", references, "https://other.example/recipes") }
            assertThrows(IllegalStateException::class.java) { LegacyImageMigration.targets(value, "uid", "doc", emptyMap<String, String>(), base) }
        }
    }
}
