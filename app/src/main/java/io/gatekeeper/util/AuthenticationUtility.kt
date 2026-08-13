package io.gatekeeper.util

import android.content.Intent
import android.net.Uri
import android.util.Log
import io.gatekeeper.ui.DummyActivity
import java.security.NoSuchAlgorithmException
import javax.crypto.KeyGenerator

// Opening access to actions across the profile boundary poses a security risk
// The risk is that other applications might also be able to start our activities
// through system's IntentForwarderActivity
// That activity runs in the system process, thus normal limitations like "permissions"
// and "exported" will not work.
// This class tries to fix it by appending a timestamp and a signature of the timestamp
// to our own Intents sent through the boundary, ensuring that only Shelter can invoke
// its high-privilege functions across that boundary, assuming that no other application
// would be able to access Shelter's internal storage to gain access to the private key.
// The private key is generated the first time this class is used, and then shared
// across the profile boundary. Shelter will always trust the first key it receives.
object AuthenticationUtility {
    fun signIntent(intent: Intent) {
        var key = LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_AUTH_KEY)
        if (key == null || hexStringToByteArray(key) == null) {
            key = generateKey()
            LocalStorageManager.getInstance().setString(LocalStorageManager.PREF_AUTH_KEY, key)
            intent.putExtra("auth_key", key)
        } else {
            val timestamp = System.currentTimeMillis()
            intent.putExtra("timestamp", timestamp)
            intent.putExtra("signature", AuthPayload.sign(key, signedPayload(intent, timestamp)))
        }
    }

    fun checkIntent(intent: Intent): Boolean {
        val key = LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_AUTH_KEY)
        if (key == null) {
            return if (canBootstrap(intent)) {
                val authKey = intent.getStringExtra("auth_key") ?: return false
                if (hexStringToByteArray(authKey) == null) return false
                LocalStorageManager.getInstance().setString(
                    LocalStorageManager.PREF_AUTH_KEY,
                    authKey
                )
                LocalStorageManager.getInstance().setBoolean(
                    LocalStorageManager.PREF_AUTH_BOOTSTRAPPED,
                    true
                )
                true
            } else {
                false
            }
        } else {
            val timestamp = System.currentTimeMillis()
            val intentTimestamp = intent.getLongExtra("timestamp", 0)
            val delta = timestamp - intentTimestamp
            if (delta !in -CLOCK_SKEW_MS..<MAX_AGE_MS) {
                Log.w(TAG, "intent signature timestamp rejected, deltaMs=$delta")
                return false
            }
            return AuthPayload.verify(
                key,
                signedPayload(intent, intentTimestamp),
                intent.getStringExtra("signature")
            )
        }
    }

    fun reset() {
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_KEY)
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED)
    }

    private fun canBootstrap(intent: Intent): Boolean =
        !LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED) &&
            intent.action in BOOTSTRAP_ACTIONS && intent.hasExtra("auth_key")

    private fun generateKey(): String = try {
        val keyGen = KeyGenerator.getInstance("HmacSHA256")
        keyGen.init(256)
        bytesToHex(keyGen.generateKey().encoded)
    } catch (e: NoSuchAlgorithmException) {
        throw IllegalStateException("HmacSHA256 is unavailable", e)
    }

    private val hexArray = "0123456789ABCDEF".toCharArray()

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        for (j in bytes.indices) {
            val v = bytes[j].toInt() and 0xFF
            hexChars[j * 2] = hexArray[v ushr 4]
            hexChars[j * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun hexStringToByteArray(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        return ByteArray(s.length / 2) { i ->
            val high = Character.digit(s[i * 2], 16)
            val low = Character.digit(s[i * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            ((high shl 4) + low).toByte()
        }
    }

    private fun signedPayload(intent: Intent, timestamp: Long): String =
        AuthPayload.canonicalize(intent.action, timestamp, extractSignedExtras(intent))

    private fun extractSignedExtras(intent: Intent): List<Pair<String, String>> {
        val extras = intent.extras ?: return emptyList()
        return AuthPayload.SIGNED_EXTRA_KEYS.mapNotNull { key ->
            if (!extras.containsKey(key)) return@mapNotNull null
            val value = when (val raw = extras.get(key)) {
                is String -> "s:$raw"
                is Int -> "i:$raw"
                is Long -> "l:$raw"
                is Boolean -> "b:$raw"
                is Uri -> "u:$raw"
                is Array<*> -> {
                    if (raw.all { it is String }) {
                        @Suppress("UNCHECKED_CAST")
                        AuthPayload.encodeStringArray(raw as Array<String>)
                    } else null
                }
                is BooleanArray -> "ba:${raw.joinToString(",") { it.toString() }}"
                else -> null
            } ?: return@mapNotNull null
            key to value
        }
    }

    private const val TAG = "AuthUtility"
    private const val CLOCK_SKEW_MS = 2_000L
    private const val MAX_AGE_MS = 30_000L
    private val BOOTSTRAP_ACTIONS = setOf(
        DummyActivity.TRY_START_SERVICE,
        DummyActivity.START_SERVICE,
        DummyActivity.FINALIZE_PROVISION,
    )
}
