package tech.mmarca.openvitals.features.vertmatch

import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.R
import tech.mmarca.openvitals.domain.model.ExerciseData
import tech.mmarca.openvitals.features.activity.ActivityWorkoutSeries
import tech.mmarca.openvitals.features.activity.sortedRoutePointsForExport
import tech.mmarca.openvitals.features.activity.writeActivityWorkoutFit
import tech.mmarca.openvitals.vertmatch.AuthorizationResult
import tech.mmarca.openvitals.vertmatch.ErrorAction
import tech.mmarca.openvitals.vertmatch.SummaryZone
import tech.mmarca.openvitals.vertmatch.UploadOutcome
import tech.mmarca.openvitals.vertmatch.VertMatchApi
import tech.mmarca.openvitals.vertmatch.VertMatchAuth
import tech.mmarca.openvitals.vertmatch.VertMatchConfig
import tech.mmarca.openvitals.vertmatch.VertMatchError
import tech.mmarca.openvitals.vertmatch.VertMatchResult
import tech.mmarca.openvitals.vertmatch.VertMatchScope
import tech.mmarca.openvitals.vertmatch.VertMatchSummary
import tech.mmarca.openvitals.vertmatch.VertMatchTokenClient

internal enum class VertMatchStatus {
    /** This build carries no client id: VertMatch has not approved it, so nothing can connect. */
    NotConfigured,
    Disconnected,
    Connected,

    /** VertMatch switched this app off (`client_not_allowed`, `app_suspended`): no calls at all. */
    AppStopped,
}

internal data class VertMatchUiState(
    val status: VertMatchStatus,
    val busy: Boolean = false,
    val summary: VertMatchSummary? = null,
    val sent: Set<String> = emptySet(),
    /** Deleted on VertMatch by the runner: VertMatch keeps them deleted, so the app offers no Send. */
    val known: Set<String> = emptySet(),
    @param:StringRes val message: Int? = null,
)

/**
 * The runner's connection to VertMatch: sign-in, sending and deleting activities, and the
 * training summary. One per process. Calls are serialised, so a token refresh never races
 * another call. Nothing here logs: no token, no file and no id leaves through the log.
 *
 * The vault reads the disk and the Keystore, so it is only touched on [Dispatchers.IO]: the
 * constructor reads nothing, and the stored connection loads in the background, with the state
 * busy until it has.
 */
@Singleton
internal class VertMatchConnection @Inject constructor(
    private val config: VertMatchConfig,
    private val vault: VertMatchVault,
    private val tokenClient: VertMatchTokenClient,
) {
    private val api = VertMatchApi(config, { vault.tokens?.accessToken })
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(
        VertMatchUiState(
            status = if (config.isConfigured) VertMatchStatus.Disconnected else VertMatchStatus.NotConfigured,
            busy = config.isConfigured,
        ),
    )
    val state: StateFlow<VertMatchUiState> = _state.asStateFlow()

    init {
        // serial publishes what the vault holds when it ends.
        if (config.isConfigured) scope.launch { serial {} }
    }

    val canReadSummary: Boolean get() = VertMatchScope.SummaryRead in config.scopes

    /** The browser intent that starts sign-in. The verifier waits, encrypted, for the redirect. */
    suspend fun signInIntent(): Intent = withContext(Dispatchers.IO) {
        val request = VertMatchAuth.begin(config)
        vault.pendingSignIn = PendingSignIn(request.state, request.verifier)
        Intent(Intent.ACTION_VIEW, Uri.parse(request.url))
    }

    /** True when [uri] is this app's VertMatch redirect, which [completeSignIn] then handles. */
    fun isRedirect(uri: Uri?): Boolean =
        uri != null && config.isConfigured && uri.toString().startsWith(config.redirectUri)

    suspend fun completeSignIn(redirect: Uri) = serial {
        val pending = vault.pendingSignIn ?: return@serial
        when (val result = VertMatchAuth.parseRedirect(redirect.toString(), pending.state)) {
            is AuthorizationResult.Code -> {
                vault.pendingSignIn = null
                when (val exchanged = tokenClient.exchangeCode(result.code, pending.verifier)) {
                    is VertMatchResult.Success -> {
                        val tokens = exchanged.value
                        vault.tokens = StoredTokens(tokens.accessToken, tokens.refreshToken, expiry(tokens.expiresInSeconds))
                        vault.appStopped = false
                        publish(R.string.vertmatch_message_connected)
                    }
                    is VertMatchResult.Failure -> publish(messageFor(exchanged.error))
                }
            }
            is AuthorizationResult.Denied -> {
                vault.pendingSignIn = null
                publish(R.string.vertmatch_message_declined)
            }
            // Not the redirect of the sign-in this app started: ignore it.
            AuthorizationResult.Invalid -> Unit
        }
    }

    /** Forgets the connection on this phone: tokens, the Keystore key and what was sent. */
    suspend fun disconnect() = serial {
        vault.clear()
        publish(R.string.vertmatch_message_disconnected)
    }

    /**
     * Sends [workout] as a FIT file, with its route when it has one. Sending the same activity again
     * is safe: its external id is derived from the Health Connect session id, so VertMatch answers
     * `unchanged`.
     */
    suspend fun send(workout: ExerciseData, series: ActivityWorkoutSeries) = serial {
        val externalId = externalIdFor(workout.id) ?: return@serial publish(R.string.vertmatch_message_cannot_send)
        val fit = withContext(Dispatchers.Default) {
            ByteArrayOutputStream().also { out ->
                writeActivityWorkoutFit(workout, series, out, routePoints = workout.sortedRoutePointsForExport())
            }.toByteArray()
        }
        when (val result = call { api.uploadActivity(externalId, fit) }) {
            is VertMatchResult.Success -> when (result.value) {
                is UploadOutcome.Created, is UploadOutcome.Unchanged -> {
                    vault.markSent(externalId)
                    publish(R.string.vertmatch_message_sent)
                }
                // The runner deleted it in VertMatch; it stays deleted and is not sent again.
                is UploadOutcome.Known -> {
                    vault.markKnown(externalId)
                    publish(R.string.vertmatch_message_known)
                }
                is UploadOutcome.Duplicate -> publish(R.string.vertmatch_message_duplicate)
            }
            is VertMatchResult.Failure -> publish(messageFor(result.error))
        }
        flushPendingDeletes()
    }

    /** Called after OpenVitals deleted an activity: deletes VertMatch's copy, if it has one. */
    fun onActivityDeleted(id: String) {
        val externalId = externalIdFor(id) ?: return
        scope.launch {
            serial {
                vault.forgetKnown(externalId)
                if (externalId !in vault.sent) return@serial
                vault.forgetSent(externalId)
                vault.addPendingDelete(externalId)
                flushPendingDeletes()
            }
        }
    }

    suspend fun loadSummary() = serial {
        if (!canReadSummary) return@serial
        when (val result = call { api.summary(SummaryZone.systemDefault()) }) {
            is VertMatchResult.Success -> _state.update { it.copy(summary = result.value) }
            is VertMatchResult.Failure -> publish(messageFor(result.error))
        }
        flushPendingDeletes()
    }

    private fun refresh() = _state.update { it.fromVault() }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Deletes still owed to VertMatch. A transient failure leaves them for the next call. */
    private suspend fun flushPendingDeletes() {
        for (externalId in vault.pendingDeletes) {
            when (val result = call { api.deleteActivity(externalId) }) {
                is VertMatchResult.Success -> vault.removePendingDelete(externalId)
                is VertMatchResult.Failure -> when (result.error.action) {
                    ErrorAction.RetryWithBackoff, ErrorAction.RefreshOrReauthenticate,
                    ErrorAction.ReauthenticateOnce, ErrorAction.PauseRunner,
                    -> return
                    // The copy cannot be reached any more (account gone, app stopped, not allowed).
                    else -> vault.removePendingDelete(externalId)
                }
            }
        }
    }

    /**
     * One API call with the document's handling around it: a fresh token first, one refresh and
     * retry on `TOKEN_EXPIRED`, and the connection state changed for the codes that change it.
     */
    private suspend fun <T> call(block: () -> VertMatchResult<T>): VertMatchResult<T> = withContext(Dispatchers.IO) {
        if (vault.appStopped) return@withContext stoppedFailure()
        if (vault.tokens == null) return@withContext VertMatchResult.Failure(VertMatchError("token_missing", 0, "not connected"))
        if (isExpired(vault.tokens)) refreshTokens()
        var result = block()
        if (result is VertMatchResult.Failure && result.error.action == ErrorAction.RefreshOrReauthenticate && refreshTokens()) {
            result = block()
        }
        if (result is VertMatchResult.Failure) apply(result.error.action)
        result
    }

    private fun apply(action: ErrorAction) {
        when (action) {
            // Expired and the refresh did not help. A refused refresh token already ended the session
            // (refreshTokens); one that only failed to reach Auth0 is kept for the next try.
            ErrorAction.RefreshOrReauthenticate -> if (vault.tokens?.refreshToken == null) vault.tokens = null
            ErrorAction.ReauthenticateOnce, ErrorAction.NoRetryReconnect -> vault.tokens = null
            ErrorAction.DropConnection -> vault.clear()
            ErrorAction.StopAllCalls -> vault.appStopped = true
            ErrorAction.PauseRunner, ErrorAction.RetryWithBackoff, ErrorAction.FixInput -> Unit
        }
        refresh()
    }

    /** True when the access token was renewed. A refused refresh token ends the session. */
    private fun refreshTokens(): Boolean {
        val stored = vault.tokens ?: return false
        val refreshToken = stored.refreshToken ?: return false
        return when (val result = tokenClient.refresh(refreshToken)) {
            is VertMatchResult.Success -> {
                val tokens = result.value
                vault.tokens = StoredTokens(tokens.accessToken, tokens.refreshToken, expiry(tokens.expiresInSeconds))
                true
            }
            is VertMatchResult.Failure -> {
                if (result.error.code == "invalid_grant") vault.tokens = null
                false
            }
        }
    }

    private fun isExpired(tokens: StoredTokens?): Boolean {
        val expiresAt = tokens?.expiresAtMillis ?: return false
        return System.currentTimeMillis() > expiresAt - EXPIRY_MARGIN_MILLIS
    }

    private fun expiry(expiresInSeconds: Long?): Long? = expiresInSeconds?.let { System.currentTimeMillis() + it * 1000 }

    private fun currentStatus(): VertMatchStatus = when {
        !config.isConfigured -> VertMatchStatus.NotConfigured
        vault.appStopped -> VertMatchStatus.AppStopped
        vault.tokens != null -> VertMatchStatus.Connected
        else -> VertMatchStatus.Disconnected
    }

    private fun publish(@StringRes message: Int) = _state.update { it.fromVault().copy(message = message) }

    /** What the vault says now. Called on IO only, like every other vault read. */
    private fun VertMatchUiState.fromVault() = copy(status = currentStatus(), sent = vault.sent, known = vault.known)

    /** One call at a time, on [Dispatchers.IO] wherever it was started: every step reads the vault. */
    private suspend fun serial(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        _state.update { it.copy(busy = true) }
        try {
            mutex.withLock { block() }
        } finally {
            _state.update { it.fromVault().copy(busy = false) }
        }
    }

    private fun stoppedFailure() = VertMatchResult.Failure(VertMatchError("app_suspended", 0, "switched off"))

    @StringRes
    private fun messageFor(error: VertMatchError): Int = when (error.action) {
        // Still connected after an expiry means the refresh only failed to get through.
        ErrorAction.RefreshOrReauthenticate -> if (vault.tokens != null) {
            R.string.vertmatch_message_try_later
        } else {
            R.string.vertmatch_message_sign_in_again
        }
        ErrorAction.ReauthenticateOnce, ErrorAction.NoRetryReconnect ->
            R.string.vertmatch_message_sign_in_again
        ErrorAction.DropConnection -> R.string.vertmatch_message_account_deleted
        ErrorAction.PauseRunner -> R.string.vertmatch_message_account_suspended
        ErrorAction.StopAllCalls -> R.string.vertmatch_message_app_stopped
        ErrorAction.RetryWithBackoff -> R.string.vertmatch_message_try_later
        ErrorAction.FixInput -> if (error.code == "not_load_eligible") {
            R.string.vertmatch_message_no_duration
        } else {
            R.string.vertmatch_message_refused
        }
    }

    companion object {
        private const val EXPIRY_MARGIN_MILLIS = 60_000L

        /** The redirect VertMatch must have registered for this build: one per application id. */
        fun redirectUriFor(applicationId: String): String = "$applicationId://vertmatch/callback"

        /**
         * The stable id of an activity on VertMatch: its Health Connect session id, which never changes
         * for the same session, behind a prefix that says where it came from. Null for an id VertMatch
         * would refuse (it takes visible ASCII only).
         */
        fun externalIdFor(healthConnectId: String): String? =
            "openvitals-$healthConnectId".takeIf { id ->
                healthConnectId.isNotBlank() && id.length <= 128 && id.all { it.code in 0x21..0x7E }
            }
    }
}
