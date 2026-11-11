package tech.mmarca.openvitals.vertmatch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * A request as the client builds it. It can carry a bearer token or a FIT file,
 * so it has no `toString` that prints either: log [describe] at most.
 */
public class HttpRequest(
    public val method: String,
    public val url: String,
    public val headers: Map<String, String> = emptyMap(),
    public val body: ByteArray? = null,
) {
    public fun describe(): String = "$method ${url.substringBefore('?')}"

    override fun toString(): String = describe()
}

public class HttpResponse(public val status: Int, public val body: ByteArray) {
    override fun toString(): String = "HttpResponse($status)"
}

/** The seam tests replace. Blocking: call it from a background dispatcher, never the main thread. */
public fun interface HttpTransport {
    /** @throws IOException when the network fails; any HTTP status, error ones included, is a response. */
    public fun execute(request: HttpRequest): HttpResponse
}

/** `HttpURLConnection` over HTTPS: no third-party HTTP library, so no new dependency. */
public class UrlConnectionTransport(
    private val connectTimeoutMillis: Int = 15_000,
    // A 25 MB FIT file on a slow uplink needs room.
    private val readTimeoutMillis: Int = 60_000,
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        if (!request.url.startsWith("https://")) throw IOException("refusing a non-HTTPS request")
        val connection = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.instanceFollowRedirects = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            request.body?.let { body ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return HttpResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }
}
