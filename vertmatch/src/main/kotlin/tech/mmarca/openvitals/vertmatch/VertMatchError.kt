package tech.mmarca.openvitals.vertmatch

import kotlin.random.Random

/** Success or a [VertMatchError]. The client never throws for an API or network failure. */
public sealed interface VertMatchResult<out T> {
    public class Success<T>(public val value: T) : VertMatchResult<T>

    public class Failure(public val error: VertMatchError) : VertMatchResult<Nothing>
}

/** What the app should do about a failed call: the document's "handle" rules, as data. */
public enum class ErrorAction {
    /** `TOKEN_EXPIRED`: refresh the token if refresh tokens were agreed, otherwise send the runner through sign-in again. */
    RefreshOrReauthenticate,

    /** `INVALID_TOKEN`, no token: sign in again once, then stop rather than loop. */
    ReauthenticateOnce,

    /** `ACCOUNT_DELETED`: forget the connection for this runner and stop syncing. */
    DropConnection,

    /** `ACCOUNT_SUSPENDED`: pause this runner. */
    PauseRunner,

    /** `insufficient_scope`: never retry; ask the runner to reconnect, or ask VertMatch. */
    NoRetryReconnect,

    /** `client_not_allowed`, `app_suspended`: stop every call and write to VertMatch's contact. */
    StopAllCalls,

    /** 429, upstream outage, 5xx, network failure: retry with exponential backoff and jitter, bounded. */
    RetryWithBackoff,

    /** Any other 4xx: the payload is wrong. Never resend the same one. */
    FixInput,
}

/**
 * A failed call. [code] is VertMatch's machine code (`TOKEN_EXPIRED`, `external_id_mismatch`, ...);
 * the document says to branch on it, not on [status]. [status] is 0 when nothing was sent.
 * A 429 has no code in its body, so it is reported as `rate_limited`.
 */
public class VertMatchError(
    public val code: String,
    public val status: Int,
    public val message: String,
) {
    public val action: ErrorAction = actionFor(code, status)

    override fun toString(): String = "VertMatchError($code, http=$status)"

    internal companion object {
        fun actionFor(code: String, status: Int): ErrorAction = when {
            code == "TOKEN_EXPIRED" -> ErrorAction.RefreshOrReauthenticate
            code == "INVALID_TOKEN" || code == "token_missing" -> ErrorAction.ReauthenticateOnce
            code == "ACCOUNT_DELETED" -> ErrorAction.DropConnection
            code == "ACCOUNT_SUSPENDED" -> ErrorAction.PauseRunner
            code == "insufficient_scope" -> ErrorAction.NoRetryReconnect
            code == "client_not_allowed" || code == "app_suspended" -> ErrorAction.StopAllCalls
            code == "AUTH_UPSTREAM_UNAVAILABLE" || code == "partner_not_configured" -> ErrorAction.RetryWithBackoff
            code == "network_error" || code == "rate_limited" -> ErrorAction.RetryWithBackoff
            status == 429 || status >= 500 -> ErrorAction.RetryWithBackoff
            else -> ErrorAction.FixInput
        }

        fun local(code: String, message: String) = VertMatchError(code, 0, message)
    }
}

/** Exponential backoff with full jitter, capped: `random(0, min(cap, base * 2^attempt))`. */
public fun backoffMillis(
    attempt: Int,
    random: Random = Random.Default,
    baseMillis: Long = 1_000,
    capMillis: Long = 5 * 60_000,
): Long {
    require(attempt >= 0) { "attempt must not be negative" }
    val ceiling = minOf(capMillis, baseMillis shl attempt.coerceAtMost(20))
    return random.nextLong(ceiling + 1)
}
