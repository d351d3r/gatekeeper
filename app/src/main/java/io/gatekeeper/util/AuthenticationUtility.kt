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
// to our own Intents sent through the boundary, ensuring that only Gatekeeper can invoke
// its high-privilege functions across that boundary, assuming that no other application
// would be able to access Gatekeeper's internal storage to gain access to the private key.
// The private key is generated the first time this class is used, and then shared
// across the profile boundary. Gatekeeper will always trust the first key it receives.
//
// The key itself travels only until the other side has proven it holds the key
// (PREF_AUTH_PEER_CONFIRMED). The system forwarder re-resolves a forwarded intent in the
// target profile without its component, so any app there that declares the same action
// can be picked in the "Open with" chooser and read the extras (docs/audit_android17.md, S1).
object AuthenticationUtility {
    fun signIntent(intent: Intent) {
        val storage = LocalStorageManager.getInstance()
        val stored = storage
            .getString(LocalStorageManager.PREF_AUTH_KEY)
            ?.takeIf { Hex.decode(it) != null }
        if (stored == null && intent.action !in BOOTSTRAP_ACTIONS) {
            Log.w(TAG, "cross-profile action deferred until bootstrap: ${intent.action}")
            return
        }
        val key = stored ?: currentKey()

        if (intent.action in BOOTSTRAP_ACTIONS &&
            !storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)
        ) {
            intent.putExtra("auth_key", key)
        }

        val timestamp = System.currentTimeMillis()
        intent.putExtra("timestamp", timestamp)
        intent.putExtra("signature", AuthPayload.sign(key, signedPayload(intent, timestamp)))
    }

    fun checkIntent(intent: Intent): Boolean {
        val key = LocalStorageManager.getInstance().getString(LocalStorageManager.PREF_AUTH_KEY)
        if (key == null) {
            return if (canBootstrap(intent)) {
                val authKey = intent.getStringExtra("auth_key") ?: return false
                if (Hex.decode(authKey) == null) return false
                LocalStorageManager.getInstance().setString(
                    LocalStorageManager.PREF_AUTH_KEY,
                    authKey
                )
                LocalStorageManager.getInstance().setBoolean(
                    LocalStorageManager.PREF_AUTH_BOOTSTRAPPED,
                    true
                )
                // Ключ пришел от другой стороны -- значит, он у нее есть.
                markPeerConfirmed()
                true
            } else {
                false
            }
        } else {
            val timestamp = System.currentTimeMillis()
            val intentTimestamp = intent.getLongExtra("timestamp", 0)
            if (!AuthPayload.isFresh(timestamp, intentTimestamp)) {
                Log.w(TAG, "intent signature timestamp rejected, deltaMs=${timestamp - intentTimestamp}")
                return false
            }
            val verified = AuthPayload.verify(
                key,
                signedPayload(intent, intentTimestamp),
                intent.getStringExtra("signature")
            )
            // Подпись нашим ключом сошлась -- у отправителя он есть, слать его больше незачем.
            if (verified) markPeerConfirmed()
            return verified
        }
    }

    /** Другая сторона доказала, что держит общий ключ. */
    fun markPeerConfirmed() {
        val storage = LocalStorageManager.getInstance()
        if (!storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)) {
            storage.setBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED, true)
        }
    }

    /**
     * Принять чужой ключ вместо своего; false -- ключ не годен по форме.
     * Вызывается только после подтверждения человеком в рабочем профиле
     * (ProfileRepairFlow): молчаливая смена ключа отдала бы реле любому
     * приложению, умеющему послать форварднутый интент.
     */
    fun adoptKey(key: String): Boolean {
        if (Hex.decode(key) == null) return false
        val storage = LocalStorageManager.getInstance()
        storage.setString(LocalStorageManager.PREF_AUTH_KEY, key)
        storage.setBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED, true)
        // Ключ предложила другая сторона -- он у нее есть.
        storage.setBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED, true)
        return true
    }

    /** Свой ключ для предъявления другой стороне; заводится, если его еще нет. */
    fun currentKey(): String {
        val storage = LocalStorageManager.getInstance()
        val existing = storage.getString(LocalStorageManager.PREF_AUTH_KEY)
        if (existing != null && Hex.decode(existing) != null) return existing
        val fresh = try {
            val keyGen = KeyGenerator.getInstance("HmacSHA256")
            keyGen.init(KEY_BITS)
            Hex.encode(keyGen.generateKey().encoded)
        } catch (e: NoSuchAlgorithmException) {
            throw IllegalStateException("HmacSHA256 is unavailable", e)
        }
        storage.setString(LocalStorageManager.PREF_AUTH_KEY, fresh)
        return fresh
    }

    fun reset() {
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_KEY)
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED)
        LocalStorageManager.getInstance().remove(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)
    }

    private fun canBootstrap(intent: Intent): Boolean =
        !LocalStorageManager.getInstance().getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED) &&
            intent.action in BOOTSTRAP_ACTIONS && intent.hasExtra("auth_key")

    private fun signedPayload(intent: Intent, timestamp: Long): String =
        AuthPayload.canonicalize(intent.action, timestamp, extractSignedExtras(intent))

    @Suppress("DEPRECATION") // Bundle.get(String) has no typed replacement; values are canonicalized as raw types
    private fun extractSignedExtras(intent: Intent): List<Pair<String, String>> {
        val extras = intent.extras ?: return emptyList()
        return AuthPayload.SIGNED_EXTRA_KEYS.mapNotNull { key ->
            if (!extras.containsKey(key)) return@mapNotNull null
            encodeSignedValue(extras.get(key))?.let { key to it }
        }
    }

    /**
     * Каноническая запись значения extra для подписи; null -- тип не подписывается.
     * Тип входит в запись: Int 5 и Long 5 -- разные значения для обработчика.
     */
    internal fun encodeSignedValue(raw: Any?): String? = when (raw) {
        is String -> "s:$raw"
        is Int -> "i:$raw"
        is Long -> "l:$raw"
        is Boolean -> "b:$raw"
        is Uri -> "u:$raw"
        is Array<*> -> if (raw.all { it is String }) {
            @Suppress("UNCHECKED_CAST")
            AuthPayload.encodeStringArray(raw as Array<String>)
        } else {
            null
        }
        is BooleanArray -> "ba:${raw.joinToString(",") { it.toString() }}"
        else -> null
    }

    private const val TAG = "AuthUtility"
    private const val KEY_BITS = 256
    private val BOOTSTRAP_ACTIONS = setOf(
        DummyActivity.TRY_START_SERVICE,
        DummyActivity.START_SERVICE,
        DummyActivity.FINALIZE_PROVISION,
    )
}
