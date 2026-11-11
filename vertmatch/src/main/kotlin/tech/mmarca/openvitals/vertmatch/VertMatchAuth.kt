package tech.mmarca.openvitals.vertmatch

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636), S256 only: the one method the partner API accepts. */
public object Pkce {
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    /** A fresh 43-character code verifier: 32 random bytes, base64url without padding. */
    public fun newVerifier(random: SecureRandom = SecureRandom()): String =
        base64Url.encodeToString(ByteArray(32).also(random::nextBytes))

    /** `BASE64URL(SHA256(verifier))`. */
    public fun challengeS256(verifier: String): String =
        base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}

/**
 * One authorization attempt. Open [url] in the runner's browser, keep [state] and
 * [verifier] until the redirect comes back, and never log or persist them longer
 * than that.
 */
public class AuthorizationRequest internal constructor(
    public val url: String,
    public val state: String,
    public val verifier: String,
) {
    override fun toString(): String = "AuthorizationRequest(url=$url)"
}

/** What the redirect back to the app carried. */
public sealed interface AuthorizationResult {
    public class Code(public val code: String) : AuthorizationResult {
        override fun toString(): String = "Code(redacted)"
    }

    /** The runner declined, or VertMatch refused; [error] is the OAuth `error` value. */
    public class Denied(public val error: String) : AuthorizationResult

    /** The redirect did not belong to the request: wrong or missing `state`, or no `code`. Ignore it. */
    public data object Invalid : AuthorizationResult
}

public object VertMatchAuth {
    /**
     * The authorization URL, in the shape the partner API document shows. The
     * document marks it "example; confirm with VertMatch", so confirm it with the
     * flow details sent at onboarding. `state` is added on top of the example as
     * ordinary OAuth CSRF protection.
     */
    public fun begin(config: VertMatchConfig, random: SecureRandom = SecureRandom()): AuthorizationRequest {
        check(config.isConfigured) { "VertMatch client id and redirect URI are not set" }
        val verifier = Pkce.newVerifier(random)
        val state = Pkce.newVerifier(random)
        val params = listOf(
            "response_type" to "code",
            "client_id" to config.clientId,
            "redirect_uri" to config.redirectUri,
            "audience" to config.audience,
            "scope" to config.scopeParameter,
            "code_challenge" to Pkce.challengeS256(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
        )
        val query = params.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return AuthorizationRequest("${config.authorizeEndpoint}?$query", state, verifier)
    }

    /** Reads the redirect the browser sent back to [VertMatchConfig.redirectUri]. */
    public fun parseRedirect(redirect: String, expectedState: String): AuthorizationResult {
        val params = try {
            URI(redirect) // rejects what is not a URI at all
            // Not URI.rawQuery: an opaque custom-scheme redirect (`app:cb?code=...`) has none.
            redirect.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = (it.split('=', limit = 2) + "").take(2)
                dec(k) to dec(v)
            }
        } catch (_: Exception) {
            return AuthorizationResult.Invalid
        }
        // Constant-time compare is overkill for a state the attacker cannot probe repeatedly,
        // but costs nothing.
        val state = params["state"]
        if (state == null || !MessageDigest.isEqual(state.toByteArray(), expectedState.toByteArray())) {
            return AuthorizationResult.Invalid
        }
        params["error"]?.let { return AuthorizationResult.Denied(it) }
        return params["code"]?.takeIf { it.isNotEmpty() }?.let(AuthorizationResult::Code) ?: AuthorizationResult.Invalid
    }

    // URLEncoder writes a space as '+'; the document's example uses %20.
    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

    private fun dec(value: String) = URLDecoder.decode(value, Charsets.UTF_8)
}
