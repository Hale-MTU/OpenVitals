package tech.mmarca.openvitals.vertmatch

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

class ErrorHandlingTest {
    private fun action(code: String, status: Int) = VertMatchError(code, status, "m").action

    /** One row per rule of the document's "handle" section. */
    @Test
    fun `codes map to the documented handling`() {
        assertThat(action("TOKEN_EXPIRED", 401)).isEqualTo(ErrorAction.RefreshOrReauthenticate)
        assertThat(action("INVALID_TOKEN", 401)).isEqualTo(ErrorAction.ReauthenticateOnce)
        assertThat(action("token_missing", 401)).isEqualTo(ErrorAction.ReauthenticateOnce)
        assertThat(action("ACCOUNT_DELETED", 401)).isEqualTo(ErrorAction.DropConnection)
        assertThat(action("ACCOUNT_SUSPENDED", 403)).isEqualTo(ErrorAction.PauseRunner)
        assertThat(action("insufficient_scope", 403)).isEqualTo(ErrorAction.NoRetryReconnect)
        assertThat(action("client_not_allowed", 403)).isEqualTo(ErrorAction.StopAllCalls)
        assertThat(action("app_suspended", 403)).isEqualTo(ErrorAction.StopAllCalls)
        assertThat(action("AUTH_UPSTREAM_UNAVAILABLE", 503)).isEqualTo(ErrorAction.RetryWithBackoff)
        assertThat(action("partner_not_configured", 503)).isEqualTo(ErrorAction.RetryWithBackoff)
        assertThat(action("internal_error", 500)).isEqualTo(ErrorAction.RetryWithBackoff)
        assertThat(action("rate_limited", 429)).isEqualTo(ErrorAction.RetryWithBackoff)
    }

    @Test
    fun `other client errors are never retried with the same payload`() {
        for (code in listOf(
            "file_missing", "envelope_malformed", "external_id_invalid", "unreadable_fit",
            "file_too_large", "external_id_mismatch", "not_load_eligible", "field_invalid",
        )) {
            assertThat(action(code, 400)).isEqualTo(ErrorAction.FixInput)
        }
    }

    @Test
    fun `an unknown code is decided by status`() {
        assertThat(action("brand_new", 403)).isEqualTo(ErrorAction.FixInput)
        assertThat(action("brand_new", 502)).isEqualTo(ErrorAction.RetryWithBackoff)
    }

    @Test
    fun `backoff grows and is capped`() {
        val ceilings = (0..4).map { attempt ->
            (1..200).maxOf { backoffMillis(attempt, Random(it)) }
        }
        assertThat(ceilings.zipWithNext().all { (a, b) -> b >= a }).isTrue()
        assertThat(ceilings[0]).isAtMost(1_000)
        assertThat(ceilings[3]).isAtMost(8_000)
        assertThat(backoffMillis(30, Random(1), capMillis = 60_000)).isAtMost(60_000)
        assertThat(backoffMillis(0, Random(1))).isAtLeast(0)
    }

    @Test
    fun `negative attempts are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { backoffMillis(-1) }
    }
}
