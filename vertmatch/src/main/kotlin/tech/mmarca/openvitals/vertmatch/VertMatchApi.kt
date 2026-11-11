package tech.mmarca.openvitals.vertmatch

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Supplies the runner's current access token, or null when the runner is not connected. */
public fun interface AccessTokenProvider {
    public fun accessToken(): String?
}

/** What the upload answered, for a stored activity. */
public class StoredActivity(
    public val activityId: String,
    public val externalId: String,
    public val name: String?,
    public val startDate: String?,
    public val sportFamily: String?,
    public val primarySource: String?,
    public val supersededCopy: Boolean,
    public val flags: List<String>,
)

/**
 * The four answers to an upload (HTTP 200 each). All four are success: treat them so
 * and move on. Only [Known] means "do not send this activity again".
 */
public sealed interface UploadOutcome {
    public val activityId: String
    public val externalId: String

    /** Stored for the first time. */
    public class Created(public val activity: StoredActivity) : UploadOutcome {
        override val activityId: String get() = activity.activityId
        override val externalId: String get() = activity.externalId
    }

    /** Already stored and nothing changed. A resend is safe, which is why `externalId` must be stable. */
    public class Unchanged(public val activity: StoredActivity) : UploadOutcome {
        override val activityId: String get() = activity.activityId
        override val externalId: String get() = activity.externalId
    }

    /** The runner deleted it in VertMatch, so it was NOT recreated. Do not resend it. */
    public class Known(override val activityId: String, override val externalId: String) : UploadOutcome

    /** The same activity already arrived from another source; [activityId] is that other copy. */
    public class Duplicate(
        override val activityId: String,
        override val externalId: String,
        public val provider: String?,
    ) : UploadOutcome
}

/** How the runner's "this week" is cut. A [IanaZone] wins over an offset when both could be sent. */
public sealed interface SummaryZone {
    public class IanaZone(public val id: String) : SummaryZone

    public class UtcOffset(public val minutes: Int) : SummaryZone {
        init {
            require(minutes in -720..840) { "utcOffsetMin must be within -720..840" }
        }
    }

    public companion object {
        public fun systemDefault(): SummaryZone = IanaZone(ZoneId.systemDefault().id)
    }
}

public class TargetRace(public val raceId: String?, public val name: String?, public val date: String?)

/** Training load, on the TSS scale (one hour at threshold is about 100). Not TEP. */
public class WeekLoad(public val load: Double?, public val ctl: Double?, public val atl: Double?)

/** `week` is null unless the runner is premium; `targetRace` is free. */
public class VertMatchSummary(
    public val updatedAt: String?,
    public val lang: String?,
    public val targetRace: TargetRace?,
    public val week: WeekLoad?,
)

/**
 * The partner API: upload an activity, delete it, read the summary. Every call is blocking
 * and returns a [VertMatchResult]; run it off the main thread. Nothing here logs.
 *
 * `workouts.write` and `summary.read` are checked against [VertMatchConfig.scopes]
 * before a call is made, so an app that was not approved for a scope never sends it.
 */
public class VertMatchApi(
    private val config: VertMatchConfig,
    private val tokens: AccessTokenProvider,
    private val transport: HttpTransport = UrlConnectionTransport(),
    private val random: SecureRandom = SecureRandom(),
) {
    /**
     * Uploads [fit] under a stable [externalId]: the same activity must always carry the same one,
     * and when the FIT file carries an id of its own (`virtual_ride_id`, else `ride_id`) it must equal it,
     * or VertMatch answers 422 `external_id_mismatch`.
     */
    public fun uploadActivity(externalId: String, fit: ByteArray): VertMatchResult<UploadOutcome> {
        scopeCheck(VertMatchScope.WorkoutsWrite)?.let { return it }
        validateExternalId(externalId)?.let { return it }
        if (fit.isEmpty()) return fail("unreadable_fit", "the FIT file is empty")
        if (fit.size > MAX_FIT_BYTES) return fail("file_too_large", "FIT files are limited to 25 MB")

        val boundary = "vm" + ByteArray(12).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val body = multipart(boundary, externalId, fit)
        return send(
            method = "POST",
            path = ACTIVITIES,
            contentType = "multipart/form-data; boundary=$boundary",
            body = body,
            parse = { parseUpload(it) },
        )
    }

    /**
     * Deletes the activity the runner deleted in the app. Idempotent: false means it was already gone
     * or never reached VertMatch, which is the same outcome for the app.
     */
    public fun deleteActivity(externalId: String): VertMatchResult<Boolean> {
        scopeCheck(VertMatchScope.WorkoutsWrite)?.let { return it }
        validateExternalId(externalId)?.let { return it }
        return send(
            method = "DELETE",
            path = "$ACTIVITIES?externalId=${urlEncode(externalId)}",
            parse = { it.field("deleted").asBoolean() ?: false },
        )
    }

    /** Reads the training summary. Pass the runner's zone so "this week" means their week. */
    public fun summary(zone: SummaryZone = SummaryZone.systemDefault()): VertMatchResult<VertMatchSummary> {
        scopeCheck(VertMatchScope.SummaryRead)?.let { return it }
        val query = when (zone) {
            is SummaryZone.IanaZone -> "tz=${urlEncode(zone.id)}"
            is SummaryZone.UtcOffset -> "utcOffsetMin=${zone.minutes}"
        }
        return send(method = "GET", path = "$SUMMARY?$query", parse = { parseSummary(it) })
    }

    private fun <T> send(
        method: String,
        path: String,
        contentType: String? = null,
        body: ByteArray? = null,
        parse: (JsonObject) -> T,
    ): VertMatchResult<T> {
        val token = tokens.accessToken()?.takeIf { it.isNotBlank() }
            ?: return VertMatchResult.Failure(VertMatchError("token_missing", 401, "the runner is not connected"))
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/json")
            contentType?.let { put("Content-Type", it) }
        }
        val response = try {
            transport.execute(HttpRequest(method, config.apiBase.trimEnd('/') + path, headers, body))
        } catch (_: IOException) {
            // The exception text can carry a host or a path; the code is all the caller needs.
            return fail("network_error", "the request did not complete")
        }
        val json = parseObject(response.body)
        if (response.status !in 200..299) return VertMatchResult.Failure(errorFrom(response.status, json))
        if (json == null) return fail("unexpected_response", "the answer was not a JSON object")
        return try {
            VertMatchResult.Success(parse(json))
        } catch (_: IllegalArgumentException) {
            fail("unexpected_response", "the answer did not have the documented shape")
        } catch (_: IllegalStateException) {
            fail("unexpected_response", "the answer did not have the documented shape")
        }
    }

    private fun scopeCheck(scope: VertMatchScope): VertMatchResult.Failure? =
        if (config.has(scope)) null else fail("insufficient_scope", "${scope.value} was not requested by this app")

    private fun validateExternalId(id: String): VertMatchResult.Failure? =
        // The server's rule exactly (coldeberg_activities.external_id_of): visible ASCII only.
        if (id.length in 1..MAX_EXTERNAL_ID && id.all { it.code in 0x21..0x7E }) {
            null
        } else {
            fail("external_id_invalid", "externalId must be 1-$MAX_EXTERNAL_ID visible ASCII characters")
        }

    private fun fail(code: String, message: String) = VertMatchResult.Failure(VertMatchError.local(code, message))

    internal companion object {
        const val ACTIVITIES = "/api/coldeberg/activities"
        const val SUMMARY = "/api/coldeberg/summary"
        const val MAX_FIT_BYTES = 25 * 1024 * 1024
        const val MAX_EXTERNAL_ID = 128

        private val lenient = Json { ignoreUnknownKeys = true }

        fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        /** `file`: the FIT bytes; `envelope`: the JSON text carrying the external id. */
        fun multipart(boundary: String, externalId: String, fit: ByteArray): ByteArray {
            val envelope = JsonObject(mapOf("externalId" to JsonPrimitive(externalId))).toString()
            val out = ByteArrayOutputStream(fit.size + 512)
            fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
            text("--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"file\"; filename=\"activity.fit\"\r\n")
            text("Content-Type: application/octet-stream\r\n\r\n")
            out.write(fit)
            text("\r\n--$boundary\r\n")
            text("Content-Disposition: form-data; name=\"envelope\"\r\n\r\n")
            text(envelope)
            text("\r\n--$boundary--\r\n")
            return out.toByteArray()
        }

        fun parseObject(body: ByteArray): JsonObject? = try {
            lenient.parseToJsonElement(body.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }

        /** The error body is `{"error": "<human>", "code": "<machine>"}`; a 429 has no code at all. */
        fun errorFrom(status: Int, json: JsonObject?): VertMatchError {
            val code = json?.get("code")?.asString()
                ?: if (status == 429) "rate_limited" else "http_$status"
            val message = json?.get("error")?.asString() ?: "HTTP $status"
            return VertMatchError(code, status, message)
        }

        fun parseUpload(json: JsonObject): UploadOutcome {
            val activityId = json.field("activityId").asString() ?: error("no activityId")
            val externalId = json.field("externalId").asString() ?: error("no externalId")
            return when (val status = json.field("status").asString()) {
                "created" -> UploadOutcome.Created(storedActivity(json, activityId, externalId))
                "unchanged" -> UploadOutcome.Unchanged(storedActivity(json, activityId, externalId))
                "known" -> UploadOutcome.Known(activityId, externalId)
                "duplicate" -> UploadOutcome.Duplicate(activityId, externalId, json["provider"].asString())
                else -> error("unknown upload status $status")
            }
        }

        private fun storedActivity(json: JsonObject, activityId: String, externalId: String) = StoredActivity(
            activityId = activityId,
            externalId = externalId,
            name = json["name"].asString(),
            startDate = json["startDate"].asString(),
            sportFamily = json["sportFamily"].asString(),
            primarySource = json["primarySource"].asString(),
            supersededCopy = json["supersededCopy"].asBoolean() ?: false,
            flags = (json["flags"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.asString() }
                .orEmpty(),
        )

        fun parseSummary(json: JsonObject): VertMatchSummary = VertMatchSummary(
            updatedAt = json["updatedAt"].asString(),
            lang = json["lang"].asString(),
            targetRace = (json["targetRace"] as? JsonObject)?.let {
                TargetRace(it["raceId"].asString(), it["name"].asString(), it["date"].asString())
            },
            week = (json["week"] as? JsonObject)?.let {
                WeekLoad(it["load"].asDouble(), it["ctl"].asDouble(), it["atl"].asDouble())
            },
        )

        private fun JsonObject.field(name: String): JsonElement = this[name] ?: error("missing $name")

        private fun JsonElement?.asString(): String? =
            (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.contentOrNull

        private fun JsonElement?.asBoolean(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

        private fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.doubleOrNull
    }
}
