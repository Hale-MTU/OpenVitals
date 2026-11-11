package tech.mmarca.openvitals.vertmatch

import java.io.IOException

/** Answers with a canned response and remembers what was sent. */
internal class FakeTransport(
    private val respond: (HttpRequest) -> HttpResponse,
) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return respond(request)
    }

    companion object {
        fun json(status: Int, body: String) = FakeTransport { HttpResponse(status, body.toByteArray()) }

        fun failing() = FakeTransport { throw IOException("boom: secret-host.example") }
    }
}

internal val testConfig = VertMatchConfig(
    clientId = "client-123",
    redirectUri = "tech.mmarca.openvitals://vertmatch",
    tokenEndpoint = "https://auth.example.test/token",
)

internal fun api(transport: HttpTransport, token: String? = "tok-secret", config: VertMatchConfig = testConfig) =
    VertMatchApi(config, { token }, transport)

internal fun <T> VertMatchResult<T>.value(): T = (this as VertMatchResult.Success).value

internal fun VertMatchResult<*>.error(): VertMatchError = (this as VertMatchResult.Failure).error
