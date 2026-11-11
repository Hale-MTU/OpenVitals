package tech.mmarca.openvitals.features.vertmatch

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The tokens of the connected runner, with when the access token stops working. */
internal class StoredTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtMillis: Long?,
) {
    override fun toString(): String = "StoredTokens(redacted)"
}

/** A sign-in that left for the browser and has not come back yet. */
internal class PendingSignIn(val state: String, val verifier: String) {
    override fun toString(): String = "PendingSignIn(redacted)"
}

/**
 * What the VertMatch connection keeps on the phone, in its own private preferences file.
 *
 * Tokens and the PKCE verifier are encrypted with an AES-GCM key held in the Android Keystore,
 * which never leaves the device: a backup restored elsewhere cannot decrypt them, so it reads as
 * "not connected" rather than as someone else's session. The ids of activities sent, and of
 * deletes still owed, are not secret and are kept as they are.
 */
internal class VertMatchVault(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var tokens: StoredTokens?
        get() = decrypt(prefs.getString(KEY_TOKENS, null))?.let(::parseTokens)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_TOKENS) else putString(KEY_TOKENS, encrypt(tokensJson(value)))
        }.apply()

    var pendingSignIn: PendingSignIn?
        get() = decrypt(prefs.getString(KEY_PENDING, null))?.split('\n')?.takeIf { it.size == 2 }
            ?.let { PendingSignIn(it[0], it[1]) }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_PENDING) else putString(KEY_PENDING, encrypt("${value.state}\n${value.verifier}"))
        }.apply()

    /** External ids of the activities VertMatch holds a copy of, so a delete here deletes there. */
    val sent: Set<String> get() = prefs.getStringSet(KEY_SENT, emptySet()).orEmpty().toSet()

    /** External ids whose delete on VertMatch has not gone through yet. */
    val pendingDeletes: Set<String> get() = prefs.getStringSet(KEY_PENDING_DELETES, emptySet()).orEmpty().toSet()

    /**
     * Set when VertMatch answered `client_not_allowed` or `app_suspended`: the document says to stop
     * every call. Cleared when the runner connects again.
     */
    var appStopped: Boolean
        get() = prefs.getBoolean(KEY_STOPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_STOPPED, value).apply()

    fun markSent(externalId: String) = updateSet(KEY_SENT) { it + externalId }

    fun forgetSent(externalId: String) = updateSet(KEY_SENT) { it - externalId }

    fun addPendingDelete(externalId: String) = updateSet(KEY_PENDING_DELETES) { it + externalId }

    fun removePendingDelete(externalId: String) = updateSet(KEY_PENDING_DELETES) { it - externalId }

    /** Disconnect: everything goes, the Keystore key included. */
    fun clear() {
        prefs.edit().clear().apply()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun updateSet(key: String, change: (Set<String>) -> Set<String>) {
        val current = prefs.getStringSet(key, emptySet()).orEmpty().toSet()
        prefs.edit().putStringSet(key, change(current)).apply()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** Null when absent, or when the key is gone or the bytes were not sealed by it. */
    private fun decrypt(stored: String?): String? {
        if (stored == null) return null
        val key = runCatching { existingKey() }.getOrNull() ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }
        .generateKey()

    private fun tokensJson(tokens: StoredTokens): String = JsonObject(
        buildMap {
            put("a", JsonPrimitive(tokens.accessToken))
            tokens.refreshToken?.let { put("r", JsonPrimitive(it)) }
            tokens.expiresAtMillis?.let { put("e", JsonPrimitive(it)) }
        },
    ).toString()

    private fun parseTokens(json: String): StoredTokens? = runCatching {
        val obj = Json.parseToJsonElement(json) as JsonObject
        StoredTokens(
            accessToken = obj.getValue("a").jsonPrimitive.content,
            refreshToken = obj["r"]?.jsonPrimitive?.contentOrNull,
            expiresAtMillis = obj["e"]?.jsonPrimitive?.longOrNull,
        )
    }.getOrNull()

    private companion object {
        const val PREFS = "vertmatch"
        const val KEY_TOKENS = "tokens"
        const val KEY_PENDING = "pending_sign_in"
        const val KEY_SENT = "sent"
        const val KEY_PENDING_DELETES = "pending_deletes"
        const val KEY_STOPPED = "app_stopped"
        const val KEY_ALIAS = "openvitals_vertmatch"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
