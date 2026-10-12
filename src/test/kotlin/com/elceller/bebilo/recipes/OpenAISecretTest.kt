package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.generation.OpenAISecret
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class OpenAISecretTest {
    @TempDir lateinit var directory: Path

    @Test fun `direct key supports local and environment configuration`() {
        assertEquals("dummy-local", OpenAISecret(null, "dummy-local").read())
        assertThrows(IllegalArgumentException::class.java) { OpenAISecret(null, " ").read() }
    }

    @Test fun `mounted production secret takes priority and does not silently fall back`() {
        val file = directory.resolve("key")
        Files.writeString(file, "dummy-google\n")
        assertEquals("dummy-google", OpenAISecret(file.toString(), "dummy-local").read())
        Files.delete(file)
        val failure = assertThrows(IllegalStateException::class.java) {
            OpenAISecret(file.toString(), "dummy-local").read()
        }
        assertEquals("Cannot read LLM secret", failure.message)
    }
}
