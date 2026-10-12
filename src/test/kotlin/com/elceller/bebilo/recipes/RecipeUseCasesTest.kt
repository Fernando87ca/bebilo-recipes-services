package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecipeUseCasesTest {
    @Test fun `duplicate IDs and mismatched individual responses fail`() = runTest {
        val auth = mockk<AuthUserRepository>(); coEvery { auth.getUserUid("token") } returns "a"
        val repository = mockk<RecipeRepository>(); coEvery { repository.getAll("a") } returns listOf(recipe(), recipe())
        coEvery { repository.get("a", "other") } returns recipe()
        assertThrows(InvalidStoredRecipeException::class.java) { kotlinx.coroutines.runBlocking { GetRecipesUseCase(auth) { repository }("token") } }
        assertThrows(InvalidStoredRecipeException::class.java) { kotlinx.coroutines.runBlocking { GetRecipeUseCase(auth) { repository }("token", "other") } }
    }
    @Test fun `cancellation does not become a storage error or empty library`() = runTest {
        val auth = mockk<AuthUserRepository>(); coEvery { auth.getUserUid("token") } returns "a"
        val repository = mockk<RecipeRepository>(); coEvery { repository.getAll("a") } throws CancellationException("cancelled")
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { GetRecipesUseCase(auth) { repository }("token") } }
    }
}
