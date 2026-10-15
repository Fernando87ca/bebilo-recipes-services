package com.elceller.bebilo.recipes.data.repository

import com.elceller.bebilo.recipes.domain.model.*
import com.elceller.bebilo.recipes.domain.repository.CreationContextRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

class KtorCreationContextRepository(
    private val settingsBaseUrl: String,
    private val babiesBaseUrl: String,
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) { requestTimeoutMillis = 5000; connectTimeoutMillis = 3000 }
    },
) : CreationContextRepository, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun settings(token: String): AiSettings = decode(get("$settingsBaseUrl/ai-configuration", token))
    override suspend fun babies(token: String): List<CreationBaby> = decode<List<BabyContextDto>>(
        get("$babiesBaseUrl/get-babies", token)).map { value ->
            if (value.feedingStage !in listOf(null, "Puré", "Mixto", "Trocito")) throw DependencyResponseException()
            CreationBaby(value.id, value.name, value.birthDate, value.feedingStage, value.preferredTexture,
                value.restrictions, value.foodInterest, value.description)
        }
    private suspend fun get(url: String, token: String): String {
        val response = try { client.get(url) { bearerAuth(token) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw ServiceUnavailableException() }
        when (response.status.value) {
            200 -> return try { response.bodyAsText() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { throw ServiceUnavailableException() }
            401, 404 -> throw UnauthorizedException()
            403 -> throw ForbiddenException()
            502, 503, 504 -> throw ServiceUnavailableException()
            else -> throw DependencyResponseException()
        }
    }
    private inline fun <reified T> decode(body: String): T = try { json.decodeFromString(body) }
    catch (_: Exception) { throw DependencyResponseException() }
    override fun close() = client.close()
}

/** Required upstream restrictions must never silently become an empty list. */
@Serializable
private data class BabyContextDto(
    val id: String, val name: String, val birthDate: String,
    val preferredTexture: String, val restrictions: List<String>, val foodInterest: String, val description: String,
    val feedingStage: String? = null,
)
