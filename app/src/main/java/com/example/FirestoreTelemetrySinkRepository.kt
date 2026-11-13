package com.example

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@IgnoreExtraProperties
data class CloudTelemetryRun(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sessionId: String = "",
    val deviceModel: String = "",
    val socBadge: String = "",
    val executionProvider: String = "",
    val promptTokens: Long = 0L,
    val generatedTokens: Long = 0L,
    val batchTokenizeMs: Double = 0.0,
    val gpuPrefetchMs: Double = 0.0,
    val prefillTokPerSec: Double = 0.0,
    val decodeTokPerSec: Double = 0.0,
    val avgNpuStepMs: Double = 0.0,
    val peakNpuUtilPercent: Long = 0L,
    val thermalStatusLabel: String = "Nominal",
    val isThermalThrottling: Boolean = false,
    val nativeHeapMb: Long = 0L,
    val availRamMb: Long = 0L,
    val inspectDurationMs: Long = 0L,
    val cloneDurationMs: Long = 0L,
    val compactedTurns: Long = 0L,
    val status: String = "COMPLETED",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

/**
 * Built-In AI Studio Cloud Firestore Telemetry Sink Repository:
 * - Always initializes `FirebaseFirestore` with `R.string.firestore_database_id`
 * - Enforces Google Sign-In user scoping (`/users/{userId}/telemetry_runs/{runId}`)
 * - Uses `FieldValue.serverTimestamp()` for `createdAt` and `updatedAt`
 * - Uses `handleFirestoreError(...)` on every Firestore operation.
 */
class FirestoreTelemetrySinkRepository(private val db: FirebaseFirestore) {

    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private val auth: FirebaseAuth
        get() = Firebase.auth

    private fun requireUserId(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Cloud Firestore Telemetry Sink.")
    }

    fun observeTelemetryRuns(): Flow<List<CloudTelemetryRun>> = flow {
        val uid = requireUserId()
        val path = "users/$uid/telemetry_runs"
        emitAll(
            db.collection("users")
                .document(uid)
                .collection("telemetry_runs")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(50)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CloudTelemetryRun::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun getUserTelemetryRuns(): Result<List<CloudTelemetryRun>> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, "users/unauthenticated/telemetry_runs")
            return Result.failure(e)
        }
        val path = "users/$uid/telemetry_runs"
        return try {
            val snapshot = db.collection("users")
                .document(uid)
                .collection("telemetry_runs")
                .get()
                .await()
            val runs = snapshot.toObjects(
                CloudTelemetryRun::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            Result.success(runs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    suspend fun getTelemetryRunById(targetUserId: String, runId: String): Result<CloudTelemetryRun?> {
        val path = "users/$targetUserId/telemetry_runs/$runId"
        return try {
            val doc = db.collection("users")
                .document(targetUserId)
                .collection("telemetry_runs")
                .document(runId)
                .get()
                .await()
            Result.success(
                doc.toObject(
                    CloudTelemetryRun::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    private fun sanitizeNonNegDouble(value: Float): Double {
        return if (value.isNaN() || value.isInfinite() || value < 0f) 0.0 else value.toDouble()
    }

    suspend fun recordTelemetryRun(run: TelemetryRunEntity): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "users/unauthenticated/telemetry_runs")
            return Result.failure(e)
        }
        val safeRunId = run.id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(100).ifBlank {
            "run_${System.currentTimeMillis()}"
        }
        val docRef = db.collection("users")
            .document(uid)
            .collection("telemetry_runs")
            .document(safeRunId)

        val deviceLabel = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Android Device" }.take(120)
        val safeSessionId = run.sessionId.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "default_session" }.take(120)
        val payload = mapOf(
            "userId" to uid,
            "sessionId" to safeSessionId,
            "deviceModel" to deviceLabel,
            "socBadge" to run.socBadge.ifBlank { "Snapdragon Hexagon HTP" }.take(160),
            "executionProvider" to run.executionProvider.ifBlank { "AUTO_NPU_GPU" }.take(80),
            "promptTokens" to run.promptTokens.coerceIn(0, 1_000_000).toLong(),
            "generatedTokens" to run.generatedTokens.coerceIn(0, 1_000_000).toLong(),
            "batchTokenizeMs" to sanitizeNonNegDouble(run.batchTokenizeMs),
            "gpuPrefetchMs" to sanitizeNonNegDouble(run.gpuPrefetchMs),
            "prefillTokPerSec" to sanitizeNonNegDouble(run.prefillTokPerSec),
            "decodeTokPerSec" to sanitizeNonNegDouble(run.decodeTokPerSec),
            "avgNpuStepMs" to sanitizeNonNegDouble(run.avgNpuStepMs),
            "peakNpuUtilPercent" to run.peakNpuUtilPercent.coerceIn(0, 100).toLong(),
            "thermalStatusLabel" to run.thermalStatusLabel.ifBlank { "Nominal" }.take(80),
            "isThermalThrottling" to run.isThermalThrottling,
            "nativeHeapMb" to run.nativeHeapMb.coerceIn(0, 65536).toLong(),
            "availRamMb" to run.availRamMb.coerceIn(0, 65536).toLong(),
            "inspectDurationMs" to run.inspectDurationMs.coerceAtLeast(0L),
            "cloneDurationMs" to run.cloneDurationMs.coerceAtLeast(0L),
            "compactedTurns" to run.compactedTurns.coerceAtLeast(0).toLong(),
            "status" to run.status.ifBlank { "COMPLETED" }.take(160),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun syncAllLocalRunsToFirestore(runs: List<TelemetryRunEntity>): Result<Int> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        var syncedCount = 0
        var lastError: Throwable? = null
        for (r in runs) {
            val res = recordTelemetryRun(r)
            if (res.isSuccess) {
                syncedCount++
            } else {
                lastError = res.exceptionOrNull()
            }
        }
        return if (syncedCount > 0 || runs.isEmpty()) {
            Result.success(syncedCount)
        } else {
            Result.failure(lastError ?: IllegalStateException("Failed syncing telemetry runs to Cloud Firestore"))
        }
    }
}

/**
 * Reactive FirebaseAuth state Flow for Jetpack Compose UI.
 */
internal fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

/**
 * Google Sign-In via Jetpack Credential Manager (`GetSignInWithGoogleOption` & `GetGoogleIdOption`).
 * One credential option per request for Android 16 (API 36) responsiveness.
 */
object GoogleCredentialAuthHelper {
    private const val TAG = "Auth"

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            onUnauthenticated()
            return
        }

        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val result = credentialManager.getCredential(context, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: () -> Unit = {}
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
            return
        }

        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()

        scope.launch {
            try {
                val activity = context as? Activity
                    ?: throw IllegalStateException("Google Sign-In requires an Activity context")
                val result = credentialManager.getCredential(activity, request)
                val credential = result.credential
                if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                    val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
                    Firebase.auth.signInWithCredential(authCredential).await()
                    onAuthSuccess()
                } else {
                    onAuthError("Unexpected credential type")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Sign in failed")
            }
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
