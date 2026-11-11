package tech.mmarca.openvitals.vertmatch

/** The two scopes the partner API defines. An app only ever asks for the ones VertMatch approved. */
public enum class VertMatchScope(public val value: String) {
    /** Upload a ride (FIT) and delete it again. */
    WorkoutsWrite("workouts.write"),

    /** Read the training summary. */
    SummaryRead("summary.read"),
}

/**
 * Everything that differs between apps and environments.
 *
 * [clientId] and [redirectUri] are issued by VertMatch when it approves the app
 * (partner-api.llm.txt, process step 3). Until then [isConfigured] is false and
 * the app must keep the integration hidden.
 *
 * Sign-in is Auth0 on VertMatch's custom domain, so [tokenEndpoint] is Auth0's
 * `/oauth/token` there. The public partner document does not name it; the
 * VertMatch backend does (`extensions.get_auth0_domain() + "/oauth/token"`).
 *
 * [requestRefreshToken] adds Auth0's `offline_access` scope. Without it Auth0
 * issues no refresh token and the runner signs in again every time the access
 * token expires. It only works if VertMatch agreed refresh tokens for this app
 * (offline access allowed on the Partner API in Auth0), so ask for it in the
 * application.
 */
public class VertMatchConfig(
    public val clientId: String,
    public val redirectUri: String,
    public val tokenEndpoint: String? = "https://auth.vertmatch.run/oauth/token",
    public val requestRefreshToken: Boolean = false,
    public val scopes: Set<VertMatchScope> = setOf(VertMatchScope.WorkoutsWrite, VertMatchScope.SummaryRead),
    public val apiBase: String = "https://api.vertmatch.run",
    public val authorizeEndpoint: String = "https://auth.vertmatch.run/authorize",
    public val audience: String = "https://api.vertmatch.run/partner",
) {
    init {
        // The partner requirements say HTTPS only; refusing anything else here
        // means a typo cannot send a bearer token in clear text.
        require(apiBase.startsWith("https://")) { "apiBase must be https" }
        require(authorizeEndpoint.startsWith("https://")) { "authorizeEndpoint must be https" }
        require(tokenEndpoint == null || tokenEndpoint.startsWith("https://")) { "tokenEndpoint must be https" }
        require(scopes.isNotEmpty()) { "at least one scope is required" }
    }

    /** True once VertMatch has issued this app its client id and registered a redirect URI. */
    public val isConfigured: Boolean get() = clientId.isNotBlank() && redirectUri.isNotBlank()

    internal fun has(scope: VertMatchScope): Boolean = scope in scopes

    /** The `scope` parameter: the API scopes, plus `offline_access` when a refresh token is wanted. */
    internal val scopeParameter: String
        get() = (scopes.map { it.value } + listOfNotNull("offline_access".takeIf { requestRefreshToken }))
            .joinToString(" ")
}
