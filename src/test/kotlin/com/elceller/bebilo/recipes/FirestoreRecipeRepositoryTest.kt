package com.elceller.bebilo.recipes

import com.elceller.bebilo.recipes.data.repository.FirestoreRecipeRepository
import com.elceller.bebilo.recipes.domain.model.*
import com.google.api.core.ApiFutures
import com.google.api.gax.grpc.GrpcStatusCode
import com.google.api.gax.rpc.UnavailableException
import com.google.api.gax.rpc.PermissionDeniedException
import com.google.cloud.firestore.*
import io.grpc.Status
import io.mockk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FirestoreRecipeRepositoryTest {
    @Test fun `temporary failures are 503 and permanent failures are internal`() = runTest {
        val firestore = mockk<Firestore>()
        val collection = mockk<CollectionReference>()
        every { firestore.collection("users").document("owner").collection("recipes") } returns collection
        val repository = FirestoreRecipeRepository(firestore)
        val unavailable = UnavailableException(RuntimeException("private"), GrpcStatusCode.of(Status.Code.UNAVAILABLE), true)
        every { collection.get() } returns ApiFutures.immediateFailedFuture(unavailable)
        assertThrows(ServiceUnavailableException::class.java) { kotlinx.coroutines.runBlocking { repository.getAll("owner") } }
        val denied = PermissionDeniedException(RuntimeException("private"), GrpcStatusCode.of(Status.Code.PERMISSION_DENIED), false)
        every { collection.get() } returns ApiFutures.immediateFailedFuture(denied)
        assertThrows(InternalServiceException::class.java) { kotlinx.coroutines.runBlocking { repository.getAll("owner") } }
    }
    @Test fun `future cancellation propagates`() = runTest {
        val firestore = mockk<Firestore>()
        val collection = mockk<CollectionReference>()
        every { firestore.collection("users").document("owner").collection("recipes") } returns collection
        every { collection.get() } returns ApiFutures.immediateFailedFuture(CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { FirestoreRecipeRepository(firestore).getAll("owner") } }
    }
}
