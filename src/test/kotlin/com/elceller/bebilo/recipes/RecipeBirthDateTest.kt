package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.mapper.*
import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.*
import com.elceller.bebilo.recipes.domain.usecase.*
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RecipeBirthDateTest {
    @Test fun `completed months matches mobile calendar rules`() {
        for ((birth, reference, expected) in listOf(
            Triple("2026-10-14", "2026-10-14", 0), Triple("2026-01-31", "2026-02-28", 0),
            Triple("2026-01-31", "2026-03-01", 1), Triple("2024-02-29", "2025-02-28", 11),
            Triple("2024-02-29", "2025-03-01", 12), Triple("2025-12-14", "2026-10-13", 9),
            Triple("2025-12-14", "2026-10-14", 10))) {
            assertEquals(expected, completedMonths(birth, LocalDate.parse(reference)))
        }
    }
    private fun contexts(birth: String) = object : CreationContextRepository {
        override suspend fun settings(token: String) = AiSettings("steps", "none", "gpt-6-luna")
        override suspend fun babies(token: String) = listOf(CreationBaby("baby-1", "Nil", birth))
    }
    @Test fun `each request recalculates using Madrid and dispatched snapshot stays fixed`() = runTest {
        val repo = MemoryRecipes(); val jobs = mutableListOf<GenerationJob>()
        for (instant in listOf("2026-10-13T21:59:00Z", "2026-10-13T22:01:00Z")) {
            CreateRecipeUseCase(creationAuth(), contexts("2025-12-14"), { repo },
                { GenerationDispatcher { jobs.add(it) } }, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC))(
                "token", creationRequest().toDomain())
        }
        assertEquals(listOf(9, 10), jobs.map { it.context.babies.single().ageMonths })
        assertEquals(listOf("2026-10-13", "2026-10-14"), jobs.map { it.context.referenceDate })
        assertEquals("2025-12-14", jobs.first().context.babies.single().birthDate)
        assertEquals(jobs.first().context, repo.contexts["uid" to jobs.first().documentId])
        assertEquals("2026-10-13", jobs.first().context.toStorageMap()["referenceDate"])
        assertEquals(9, jobs.first().context.babies.single().ageMonths)
    }
    @Test fun `invalid upstream dates fail before persistence or dispatch`() = runTest {
        val clock = Clock.fixed(Instant.parse("2026-10-14T10:00:00Z"), ZoneOffset.UTC)
        for (birth in listOf("", "2026-1-1", "2025-02-29", "2026-10-15", "2026-10-14T00:00:00Z")) {
            val repo = MemoryRecipes()
            assertThrows(DependencyResponseException::class.java) { kotlinx.coroutines.runBlocking {
                CreateRecipeUseCase(creationAuth(), contexts(birth), { repo },
                    { GenerationDispatcher { fail<Unit>("Must not dispatch") } }, clock)("token", creationRequest().toDomain())
            } }
            assertTrue(repo.values.isEmpty())
        }
    }
}
