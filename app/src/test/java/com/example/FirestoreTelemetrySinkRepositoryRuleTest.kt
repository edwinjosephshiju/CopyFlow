package com.example

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class FirestoreTelemetrySinkRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private fun sampleRun(id: String = "run_${UUID.randomUUID()}"): TelemetryRunEntity {
        return TelemetryRunEntity(
            id = id,
            sessionId = "session_test_1",
            timestamp = System.currentTimeMillis(),
            socBadge = "Snapdragon 8 Gen 2 (Hexagon v73 HTP)",
            executionProvider = "AUTO_NPU_GPU",
            promptTokens = 280,
            generatedTokens = 92,
            batchTokenizeMs = 4.2f,
            gpuPrefetchMs = 1.9f,
            prefillTokPerSec = 132.5f,
            decodeTokPerSec = 15.4f,
            avgNpuStepMs = 64.9f,
            peakNpuUtilPercent = 88,
            thermalStatusLabel = "Nominal",
            isThermalThrottling = false,
            nativeHeapMb = 410,
            availRamMb = 3900,
            inspectDurationMs = 180L,
            cloneDurationMs = 295L,
            compactedTurns = 2,
            status = "CLONED_DOCX_OK"
        )
    }

    @Test
    fun recordTelemetryRun_authenticatedOwner_succeedsAndReadsBack() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = FirestoreTelemetrySinkRepository(firestore)
        val runEntity = sampleRun()

        val createRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.recordTelemetryRun(runEntity)
        }
        assertTrue("Expected recordTelemetryRun to succeed: ${createRes.exceptionOrNull()}", createRes.isSuccess)
        val savedId = createRes.getOrThrow()

        val listRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.getUserTelemetryRuns()
        }
        assertTrue(listRes.isSuccess)
        assertTrue(listRes.getOrThrow().any { it.id == savedId })
    }

    @Test
    fun observeTelemetryRuns_authenticatedOwner_emitsRealtimeUpdates() = runBlocking {
        signInTestUser(ALICE_EMAIL)
        val repository = FirestoreTelemetrySinkRepository(firestore)
        val runEntity = sampleRun()
        val savedId = withTimeout(DEFAULT_TIMEOUT_MS) {
            repository.recordTelemetryRun(runEntity).getOrThrow()
        }

        val emitted = withTimeout(FLOW_TIMEOUT_MS) {
            repository.observeTelemetryRuns().first { list -> list.any { it.id == savedId } }
        }
        assertTrue(emitted.any { it.id == savedId })
    }

    @Test
    fun getTelemetryRunById_crossUserAccess_failsWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceRepo = FirestoreTelemetrySinkRepository(firestore)
        val savedId = withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceRepo.recordTelemetryRun(sampleRun()).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobRepo = FirestoreTelemetrySinkRepository(firestore)
        val bobRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobRepo.getTelemetryRunById(aliceUid, savedId)
        }
        assertTrue("Expected cross-user read to fail", bobRes.isFailure)
        val fsEx = bobRes.exceptionOrNull()?.unwrapFirestoreException()
        assertNotNull("Expected FirebaseFirestoreException", fsEx)
        assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
    }

    @Test
    fun observeTelemetryRuns_unauthenticatedUser_failsWithPermissionDenied() = runBlocking {
        auth.signOut()
        val repository = FirestoreTelemetrySinkRepository(firestore)
        try {
            withTimeout(FLOW_TIMEOUT_MS) {
                repository.observeTelemetryRuns().first()
            }
            fail("Expected unauthenticated observeTelemetryRuns to throw")
        } catch (e: Exception) {
            assertTrue(
                e is IllegalStateException ||
                    e.unwrapFirestoreException()?.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
            )
        }
    }

    private companion object {
        const val ALICE_EMAIL = "alice@copyflow.test"
        const val BOB_EMAIL = "bob@copyflow.test"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
