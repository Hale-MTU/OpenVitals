package tech.mmarca.openvitals.vertmatch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApiTest {
    private val fit = byteArrayOf(0x0E, 0x10, 0x00, 0x01, 0x2E, 0x46, 0x49, 0x54)

    private fun created(status: String = "created") = """
        {"status":"$status","activityId":"vr-1","externalId":"ride-1","name":"Zwift","startDate":"2026-11-01T10:00:00Z",
         "sportFamily":"bike","virtualSport":"ride","primarySource":"OpenVitals","course":null,"supersededCopy":false,"flags":["x"],
         "somethingNew":1}
    """.trimIndent()

    // -- upload ---------------------------------------------------------------

    @Test
    fun `upload sends a bearer multipart with the file and the envelope`() {
        val transport = FakeTransport.json(200, created())
        api(transport).uploadActivity("ride-1", fit)

        val request = transport.requests.single()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url).isEqualTo("https://api.vertmatch.run/api/coldeberg/activities")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer tok-secret")
        val contentType = request.headers.getValue("Content-Type")
        assertThat(contentType).startsWith("multipart/form-data; boundary=")
        val boundary = contentType.substringAfter("boundary=")
        val body = request.body!!.toString(Charsets.ISO_8859_1)
        assertThat(body).startsWith("--$boundary\r\n")
        assertThat(body).contains("name=\"file\"; filename=\"activity.fit\"")
        assertThat(body).contains("name=\"envelope\"\r\n\r\n{\"externalId\":\"ride-1\"}\r\n")
        assertThat(body).endsWith("\r\n--$boundary--\r\n")
        // The FIT bytes travel untouched.
        assertThat(body).contains(fit.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun `created and unchanged both carry the stored ride`() {
        val c = api(FakeTransport.json(200, created())).uploadActivity("ride-1", fit).value() as UploadOutcome.Created
        assertThat(c.activity.activityId).isEqualTo("vr-1")
        assertThat(c.activity.sportFamily).isEqualTo("bike")
        assertThat(c.activity.flags).containsExactly("x")
        assertThat(c.activity.supersededCopy).isFalse()

        val u = api(FakeTransport.json(200, created("unchanged"))).uploadActivity("ride-1", fit).value()
        assertThat(u).isInstanceOf(UploadOutcome.Unchanged::class.java)
    }

    @Test
    fun `known and duplicate are successes with their own shape`() {
        val known = api(FakeTransport.json(200, """{"status":"known","activityId":"vr-1","externalId":"ride-1"}"""))
            .uploadActivity("ride-1", fit).value()
        assertThat(known).isInstanceOf(UploadOutcome.Known::class.java)

        val dup = api(
            FakeTransport.json(200, """{"status":"duplicate","activityId":"vr-9","provider":"strava","externalId":"ride-1"}"""),
        ).uploadActivity("ride-1", fit).value() as UploadOutcome.Duplicate
        assertThat(dup.activityId).isEqualTo("vr-9")
        assertThat(dup.provider).isEqualTo("strava")
    }

    @Test
    fun `an unknown upload status is not taken for success`() {
        val error = api(FakeTransport.json(200, """{"status":"mystery","activityId":"a","externalId":"b"}"""))
            .uploadActivity("ride-1", fit).error()
        assertThat(error.code).isEqualTo("unexpected_response")
    }

    @Test
    fun `bad input never reaches the network`() {
        val transport = FakeTransport.json(200, created())
        val api = api(transport)
        assertThat(api.uploadActivity("", fit).error().code).isEqualTo("external_id_invalid")
        assertThat(api.uploadActivity("has space", fit).error().code).isEqualTo("external_id_invalid")
        assertThat(api.uploadActivity("x".repeat(129), fit).error().code).isEqualTo("external_id_invalid")
        // The server takes visible ASCII only, so a non-ASCII id would only come back as a 400.
        assertThat(api.uploadActivity("l\u00e4uft-1", fit).error().code).isEqualTo("external_id_invalid")
        assertThat(api.uploadActivity("x".repeat(128), fit)).isInstanceOf(VertMatchResult.Success::class.java)
        assertThat(api.uploadActivity("ride", ByteArray(0)).error().code).isEqualTo("unreadable_fit")
        assertThat(api.uploadActivity("ride", ByteArray(25 * 1024 * 1024 + 1)).error().code).isEqualTo("file_too_large")
        assertThat(transport.requests.size).isEqualTo(1) // only the 128-character id was sent
    }

    // -- delete ---------------------------------------------------------------

    @Test
    fun `a duplicate whose other copy has no id still parses`() {
        // The server answers str(provider_activity_id or ""), so the id can be empty.
        val body = """{"status":"duplicate","activityId":"","provider":"health_connect","externalId":"r"}"""
        val dup = api(FakeTransport.json(200, body)).uploadActivity("r", fit).value() as UploadOutcome.Duplicate
        assertThat(dup.activityId).isEmpty()
        assertThat(dup.provider).isEqualTo("health_connect")
    }

    @Test
    fun `delete encodes the external id and reads the flag`() {
        val transport = FakeTransport.json(200, """{"deleted":true,"externalId":"a/b c"}""")
        assertThat(api(transport).deleteActivity("a/b&c").value()).isTrue()
        val request = transport.requests.single()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.url).isEqualTo("https://api.vertmatch.run/api/coldeberg/activities?externalId=a%2Fb%26c")
        assertThat(request.body).isNull()
    }

    @Test
    fun `deleting something already gone is still success`() {
        assertThat(api(FakeTransport.json(200, """{"deleted":false,"externalId":"x"}""")).deleteActivity("x").value()).isFalse()
    }

    // -- summary --------------------------------------------------------------

    @Test
    fun `summary sends the zone and parses race and week`() {
        val transport = FakeTransport.json(
            200,
            """{"updatedAt":"2026-11-10T08:00:00Z","lang":"en","targetRace":{"raceId":"r1","name":"UTMB","date":"2027-08-27","coldebergCourseId":"c"},
               "today":null,"week":{"load":312.5,"ctl":48.1,"atl":60.2}}""",
        )
        val summary = api(transport).summary(SummaryZone.IanaZone("Europe/Berlin")).value()
        assertThat(transport.requests.single().url)
            .isEqualTo("https://api.vertmatch.run/api/coldeberg/summary?tz=Europe%2FBerlin")
        assertThat(summary.targetRace!!.name).isEqualTo("UTMB")
        assertThat(summary.week!!.load).isEqualTo(312.5)
        assertThat(summary.week.atl).isEqualTo(60.2)
    }

    @Test
    fun `summary offset form and null week`() {
        val transport = FakeTransport.json(200, """{"updatedAt":"t","lang":"en","targetRace":null,"today":null,"week":null}""")
        val summary = api(transport).summary(SummaryZone.UtcOffset(-300)).value()
        assertThat(transport.requests.single().url).endsWith("summary?utcOffsetMin=-300")
        assertThat(summary.week).isNull()
        assertThat(summary.targetRace).isNull()
    }

    @Test
    fun `offset outside the documented range is rejected`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SummaryZone.UtcOffset(841) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SummaryZone.UtcOffset(-721) }
    }

    // -- scopes, tokens, failures -------------------------------------------

    @Test
    fun `an app not approved for a scope never sends it`() {
        val transport = FakeTransport.json(200, "{}")
        val readOnly = VertMatchConfig("c", "r://x", scopes = setOf(VertMatchScope.SummaryRead))
        val api = api(transport, config = readOnly)
        assertThat(api.uploadActivity("x", fit).error().code).isEqualTo("insufficient_scope")
        assertThat(api.deleteActivity("x").error().code).isEqualTo("insufficient_scope")
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `no token means no request`() {
        val transport = FakeTransport.json(200, "{}")
        val error = api(transport, token = null).summary().error()
        assertThat(error.code).isEqualTo("token_missing")
        assertThat(error.action).isEqualTo(ErrorAction.ReauthenticateOnce)
        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `error body code wins over the status`() {
        val error = api(FakeTransport.json(401, """{"error":"Token expired","code":"TOKEN_EXPIRED"}""")).summary().error()
        assertThat(error.code).isEqualTo("TOKEN_EXPIRED")
        assertThat(error.status).isEqualTo(401)
        assertThat(error.action).isEqualTo(ErrorAction.RefreshOrReauthenticate)
    }

    @Test
    fun `a 429 has no code and is retried with backoff`() {
        val error = api(FakeTransport { HttpResponse(429, ByteArray(0)) }).summary().error()
        assertThat(error.code).isEqualTo("rate_limited")
        assertThat(error.action).isEqualTo(ErrorAction.RetryWithBackoff)
    }

    @Test
    fun `network failure is reported without the exception text`() {
        val error = api(FakeTransport.failing()).summary().error()
        assertThat(error.code).isEqualTo("network_error")
        assertThat(error.message).doesNotContain("secret-host")
        assertThat(error.action).isEqualTo(ErrorAction.RetryWithBackoff)
    }

    @Test
    fun `a success that is not json is an unexpected response`() {
        val error = api(FakeTransport { HttpResponse(200, "<html>".toByteArray()) }).summary().error()
        assertThat(error.code).isEqualTo("unexpected_response")
    }

    @Test
    fun `a request never prints its token or file`() {
        val transport = FakeTransport.json(200, created())
        api(transport).uploadActivity("ride-1", fit)
        val text = transport.requests.single().toString()
        assertThat(text).doesNotContain("tok-secret")
        assertThat(text).isEqualTo("POST https://api.vertmatch.run/api/coldeberg/activities")
    }
}
