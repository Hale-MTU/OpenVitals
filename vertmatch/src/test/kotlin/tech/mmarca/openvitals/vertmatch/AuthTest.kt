package tech.mmarca.openvitals.vertmatch

import com.google.common.truth.Truth.assertThat
import java.net.URI
import java.net.URLDecoder
import org.junit.Assert.assertThrows
import org.junit.Test

class AuthTest {
    @Test
    fun `challenge matches the RFC 7636 appendix B vector`() {
        assertThat(Pkce.challengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    @Test
    fun `verifier is 43 url-safe characters and differs each time`() {
        val a = Pkce.newVerifier()
        assertThat(a).matches("[A-Za-z0-9_-]{43}")
        assertThat(Pkce.newVerifier()).isNotEqualTo(a)
    }

    @Test
    fun `authorize url carries the documented parameters`() {
        val request = VertMatchAuth.begin(testConfig)
        val uri = URI(request.url)
        assertThat("${uri.scheme}://${uri.host}${uri.path}").isEqualTo("https://auth.vertmatch.run/authorize")
        // Space in the scope list is %20, as in the document's example.
        assertThat(request.url).contains("scope=workouts.write%20summary.read")
        val q = uri.rawQuery.split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
        assertThat(q["response_type"]).isEqualTo("code")
        assertThat(q["client_id"]).isEqualTo("client-123")
        assertThat(q["redirect_uri"]).isEqualTo("tech.mmarca.openvitals://vertmatch")
        assertThat(q["audience"]).isEqualTo("https://api.vertmatch.run/partner")
        assertThat(q["code_challenge_method"]).isEqualTo("S256")
        assertThat(q["code_challenge"]).isEqualTo(Pkce.challengeS256(request.verifier))
        assertThat(q["state"]).isEqualTo(request.state)
    }

    @Test
    fun `only requested scopes are asked for`() {
        val config = VertMatchConfig("c", "r://x", scopes = setOf(VertMatchScope.SummaryRead))
        assertThat(VertMatchAuth.begin(config).url).contains("scope=summary.read&")
        assertThat(VertMatchAuth.begin(config).url).doesNotContain("workouts.write")
    }

    @Test
    fun `offline_access is asked for only when a refresh token is wanted`() {
        assertThat(VertMatchAuth.begin(testConfig).url).doesNotContain("offline_access")
        val wanted = VertMatchConfig("c", "r://x", requestRefreshToken = true)
        assertThat(VertMatchAuth.begin(wanted).url).contains("scope=workouts.write%20summary.read%20offline_access&")
    }

    @Test
    fun `the default token endpoint is auth0 on the vertmatch domain`() {
        assertThat(VertMatchConfig("c", "r://x").tokenEndpoint).isEqualTo("https://auth.vertmatch.run/oauth/token")
    }

    @Test
    fun `an opaque custom scheme redirect is read too`() {
        val result = VertMatchAuth.parseRedirect("tech.mmarca.openvitals:callback?state=s1&code=c9", "s1")
        assertThat((result as AuthorizationResult.Code).code).isEqualTo("c9")
    }

    @Test
    fun `begin refuses an unconfigured app`() {
        assertThrows(IllegalStateException::class.java) { VertMatchAuth.begin(VertMatchConfig("", "")) }
    }

    @Test
    fun `redirect with the right state yields the code`() {
        val result = VertMatchAuth.parseRedirect("tech.mmarca.openvitals://vertmatch?code=abc%20d&state=s1", "s1")
        assertThat((result as AuthorizationResult.Code).code).isEqualTo("abc d")
    }

    @Test
    fun `redirect with another state is invalid`() {
        assertThat(VertMatchAuth.parseRedirect("app://cb?code=abc&state=other", "s1"))
            .isEqualTo(AuthorizationResult.Invalid)
        assertThat(VertMatchAuth.parseRedirect("app://cb?code=abc", "s1")).isEqualTo(AuthorizationResult.Invalid)
    }

    @Test
    fun `redirect with an error is a denial`() {
        val result = VertMatchAuth.parseRedirect("app://cb?error=access_denied&state=s1", "s1")
        assertThat((result as AuthorizationResult.Denied).error).isEqualTo("access_denied")
    }

    @Test
    fun `redirect without a code or not a URI is invalid`() {
        assertThat(VertMatchAuth.parseRedirect("app://cb?state=s1", "s1")).isEqualTo(AuthorizationResult.Invalid)
        assertThat(VertMatchAuth.parseRedirect("not a uri %%", "s1")).isEqualTo(AuthorizationResult.Invalid)
    }

    @Test
    fun `plain http endpoints are refused`() {
        assertThrows(IllegalArgumentException::class.java) { VertMatchConfig("c", "r://x", apiBase = "http://api.example") }
        assertThrows(IllegalArgumentException::class.java) {
            VertMatchConfig("c", "r://x", tokenEndpoint = "http://auth.example/token")
        }
    }

    @Test
    fun `token exchange posts the form and parses the answer`() {
        val transport = FakeTransport.json(200, """{"access_token":"a1","refresh_token":"r1","expires_in":3600}""")
        val tokens = VertMatchTokenClient(testConfig, transport).exchangeCode("the code", "ver").value()
        assertThat(tokens.accessToken).isEqualTo("a1")
        assertThat(tokens.refreshToken).isEqualTo("r1")
        assertThat(tokens.expiresInSeconds).isEqualTo(3600)
        assertThat(tokens.toString()).doesNotContain("a1")
        val sent = transport.requests.single()
        assertThat(sent.url).isEqualTo("https://auth.example.test/token")
        val body = sent.body!!.toString(Charsets.UTF_8)
        assertThat(body).contains("grant_type=authorization_code")
        assertThat(body).contains("code=the+code")
        assertThat(body).contains("code_verifier=ver")
        assertThat(body).contains("client_id=client-123")
    }

    @Test
    fun `token exchange without an endpoint fails without calling out`() {
        val transport = FakeTransport.json(200, "{}")
        val config = VertMatchConfig("c", "r://x", tokenEndpoint = null)
        val error = VertMatchTokenClient(config, transport).exchangeCode("c", "v").error()
        assertThat(error.code).isEqualTo("token_endpoint_unconfigured")
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `token refusal keeps the oauth error code only`() {
        val transport = FakeTransport.json(400, """{"error":"invalid_grant","error_description":"secret detail"}""")
        val error = VertMatchTokenClient(testConfig, transport).refresh("r").error()
        assertThat(error.code).isEqualTo("invalid_grant")
        assertThat(error.message).doesNotContain("secret")
    }

    @Test
    fun `refresh keeps the old refresh token when none is returned`() {
        val transport = FakeTransport.json(200, """{"access_token":"a2","expires_in":3600}""")
        val tokens = VertMatchTokenClient(testConfig, transport).refresh("r-old").value()
        assertThat(tokens.accessToken).isEqualTo("a2")
        assertThat(tokens.refreshToken).isEqualTo("r-old")
        assertThat(transport.requests.single().body!!.toString(Charsets.UTF_8)).contains("grant_type=refresh_token")
    }

    @Test
    fun `refresh takes a rotated refresh token`() {
        val transport = FakeTransport.json(200, """{"access_token":"a2","refresh_token":"r-new"}""")
        assertThat(VertMatchTokenClient(testConfig, transport).refresh("r-old").value().refreshToken).isEqualTo("r-new")
    }

    @Test
    fun `token answer without an access token is rejected`() {
        val error = VertMatchTokenClient(testConfig, FakeTransport.json(200, """{"refresh_token":"r"}""")).exchangeCode("c", "v").error()
        assertThat(error.code).isEqualTo("unexpected_response")
    }
}
