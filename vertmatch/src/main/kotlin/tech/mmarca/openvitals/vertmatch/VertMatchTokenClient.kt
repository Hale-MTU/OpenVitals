package tech.mmarca.openvitals.vertmatch

import java.io.IOException
import java.net.URLEncoder

/** The tokens of one connected runner. Store them encrypted; [toString] never prints them. */
public class TokenSet(
    public val accessToken: String,
    public val refreshToken: String?,
    /** Seconds the access token lives, as the server stated it. */
    public val expiresInSeconds: Long?,
) {
    override fun toString(): String = "TokenSet(redacted)"
}

/**
 * The standard OAuth 2.0 authorization-code and refresh-token requests (RFC 6749, with the PKCE
 * verifier of RFC 7636), sent to [VertMatchConfig.tokenEndpoint].
 *
 * VertMatch signs runners in with Auth0; these are Auth0's requests for a Native application using
 * PKCE, with no client secret. A refresh token is only issued when
 * [VertMatchConfig.requestRefreshToken] is on and VertMatch allowed offline access for the app.
 * With [VertMatchConfig.tokenEndpoint] set to null this fails with `token_endpoint_unconfigured`.
 */
public class VertMatchTokenClient(
    private val config: VertMatchConfig,
    private val transport: HttpTransport = UrlConnectionTransport(),
) {
    /** Trades the authorization [code] (from [VertMatchAuth.parseRedirect]) and its PKCE [verifier] for tokens. */
    public fun exchangeCode(code: String, verifier: String): VertMatchResult<TokenSet> = request(
        "grant_type" to "authorization_code",
        "code" to code,
        "redirect_uri" to config.redirectUri,
        "client_id" to config.clientId,
        "code_verifier" to verifier,
    )

    /**
     * Only if VertMatch agreed refresh tokens for this app. With rotation on, Auth0 answers with a new
     * refresh token and the old one stops working, so store the returned [TokenSet] whole, replacing
     * the old one. Without rotation the answer carries none and the one passed in is kept, so storing
     * the result never loses it.
     */
    public fun refresh(refreshToken: String): VertMatchResult<TokenSet> {
        val result = request(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
            "client_id" to config.clientId,
        )
        if (result !is VertMatchResult.Success || result.value.refreshToken != null) return result
        return VertMatchResult.Success(TokenSet(result.value.accessToken, refreshToken, result.value.expiresInSeconds))
    }

    private fun request(vararg form: Pair<String, String>): VertMatchResult<TokenSet> {
        val endpoint = config.tokenEndpoint
            ?: return VertMatchResult.Failure(
                VertMatchError.local("token_endpoint_unconfigured", "VertMatch has not given this app a token endpoint"),
            )
        val body = form.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }.toByteArray(Charsets.UTF_8)
        val response = try {
            transport.execute(
                HttpRequest(
                    method = "POST",
                    url = endpoint,
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Accept" to "application/json",
                    ),
                    body = body,
                ),
            )
        } catch (_: IOException) {
            return VertMatchResult.Failure(VertMatchError.local("network_error", "the request did not complete"))
        }
        val json = VertMatchApi.parseObject(response.body)
        if (response.status !in 200..299) {
            // OAuth errors are `{"error": "<code>", "error_description": ...}`; keep the code only.
            val code = json?.get("error")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: "http_${response.status}"
            return VertMatchResult.Failure(VertMatchError(code, response.status, "the token request was refused"))
        }
        val access = (json?.get("access_token") as? kotlinx.serialization.json.JsonPrimitive)?.content
        if (access.isNullOrEmpty()) {
            return VertMatchResult.Failure(
                VertMatchError.local("unexpected_response", "the token answer had no access_token"),
            )
        }
        return VertMatchResult.Success(
            TokenSet(
                accessToken = access,
                refreshToken = (json["refresh_token"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
                expiresInSeconds = (json["expires_in"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull(),
            ),
        )
    }

    private fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8)
}
