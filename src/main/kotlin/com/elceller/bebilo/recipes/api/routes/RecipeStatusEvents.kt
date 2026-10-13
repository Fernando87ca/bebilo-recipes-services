package com.elceller.bebilo.recipes.api.routes

import com.elceller.bebilo.recipes.api.response.RecipeStatusDto
import com.elceller.bebilo.recipes.api.response.RecipeStatusEventDto
import com.elceller.bebilo.recipes.domain.model.RecipeStatusChange
import com.elceller.bebilo.recipes.domain.model.ServiceUnavailableException
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.sse.SSEServerContent
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Preflight uses the same subscription that will feed the stream, so no read/listen gap exists. */
internal suspend fun ApplicationCall.streamRecipeStatuses(
    statuses: Flow<List<RecipeStatusChange>>,
    initialTimeout: Duration = 10.seconds,
    heartbeatPeriod: Duration = 30.seconds,
) {
    // The response writer can outlive the route handler. Its subscription is owned by
    // the SSE session, with the application job as a shutdown safety net.
    val subscriptionScope = CoroutineScope(currentCoroutineContext() + Job(application.coroutineContext[Job]))
    val batches = Channel<List<RecipeStatusChange>>(Channel.RENDEZVOUS)
    val observer = subscriptionScope.launch {
        try {
            statuses.collect { batches.send(it) }
            batches.close()
        } catch (exception: CancellationException) {
            batches.cancel(exception)
            throw exception
        } catch (exception: Exception) {
            batches.close(exception)
        }
    }
    try {
        val first = withTimeoutOrNull(initialTimeout) { batches.receive() }
            ?: throw ServiceUnavailableException()
        response.headers.append(HttpHeaders.CacheControl, "private, no-store")
        response.headers.append("X-Accel-Buffering", "no")
        respond(SSEServerContent(this@streamRecipeStatuses, {
            // Ktor 3.5 heartbeat is parented to the route job, which can finish
            // before the writer. Keep ours in the actual session scope instead.
            val heartbeat = launch {
                while (isActive) {
                    delay(heartbeatPeriod)
                    send(ServerSentEvent(comments = "heartbeat"))
                }
            }
            try {
                suspend fun sendBatch(batch: List<RecipeStatusChange>) {
                    batch.forEach { change ->
                        send(ServerSentEvent(event = "recipe-status", data = Json.encodeToString(
                            RecipeStatusEventDto(change.id, RecipeStatusDto.valueOf(change.status.name)))))
                    }
                }
                sendBatch(first)
                for (batch in batches) sendBatch(batch)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                // Headers are committed: terminate SSE without a second JSON response.
                application.log.warn("Recipe status stream terminated")
            } finally {
                heartbeat.cancel()
                subscriptionScope.cancel()
                withContext(NonCancellable) { observer.join() }
                batches.cancel()
            }
        }))
    } catch (exception: Throwable) {
        subscriptionScope.cancel()
        withContext(NonCancellable) { observer.join() }
        batches.cancel()
        if (exception is CancellationException || !response.isCommitted) throw exception
        application.log.warn("Recipe status response terminated")
    }
}
