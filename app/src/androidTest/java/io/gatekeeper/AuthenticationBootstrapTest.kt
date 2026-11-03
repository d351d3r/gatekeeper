package io.gatekeeper

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.ui.DummyActivity
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.KeyProof
import io.gatekeeper.util.LocalStorageManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthenticationBootstrapTest {
    private lateinit var storage: LocalStorageManager
    private var previousKey: String? = null
    private var previousBootstrapped = false
    private var previousPeerConfirmed = false

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        storage = LocalStorageManager.getInstance()
        previousKey = storage.getString(LocalStorageManager.PREF_AUTH_KEY)
        previousBootstrapped = storage.getBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED)
        previousPeerConfirmed = storage.getBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)
        storage.remove(LocalStorageManager.PREF_AUTH_KEY)
        storage.remove(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED)
        storage.remove(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)
    }

    @After
    fun tearDown() {
        storage.remove(LocalStorageManager.PREF_AUTH_KEY)
        storage.remove(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED)
        storage.remove(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED)
        previousKey?.let { storage.setString(LocalStorageManager.PREF_AUTH_KEY, it) }
        if (previousBootstrapped) {
            storage.setBoolean(LocalStorageManager.PREF_AUTH_BOOTSTRAPPED, true)
        }
        if (previousPeerConfirmed) {
            storage.setBoolean(LocalStorageManager.PREF_AUTH_PEER_CONFIRMED, true)
        }
    }

    @Test
    fun nonBootstrapActionCannotCreateTheFirstCrossProfileKey() {
        val intent = Intent(DummyActivity.SYNC_ANTI_SPY_VPN_WATCH)

        AuthenticationUtility.signIntent(intent)

        assertFalse(intent.hasExtra("auth_key"))
        assertNull(storage.getString(LocalStorageManager.PREF_AUTH_KEY))
    }

    @Test
    fun bootstrapProbeCarriesTheKeyAndASignature() {
        val intent = Intent(DummyActivity.TRY_START_SERVICE)

        AuthenticationUtility.signIntent(intent)

        assertNotNull(intent.getStringExtra("auth_key"))
        assertTrue(intent.hasExtra("timestamp"))
        assertNotNull(intent.getStringExtra("signature"))
    }

    /**
     * S1: после того как другая сторона доказала владение ключом, сам ключ в служебные
     * интенты больше не кладется -- их может прочитать подделка из системного выбора.
     */
    @Test
    fun confirmedPeerNoLongerReceivesTheKey() {
        AuthenticationUtility.currentKey()
        AuthenticationUtility.markPeerConfirmed()
        val intent = Intent(DummyActivity.START_SERVICE)

        AuthenticationUtility.signIntent(intent)

        assertFalse(intent.hasExtra("auth_key"))
        assertNotNull(intent.getStringExtra("signature"))
    }

    @Test
    fun keyProofRoundTrips() {
        AuthenticationUtility.currentKey()
        val challenge = KeyProof.newChallenge()

        val proof = KeyProof.prove(challenge)

        assertTrue(KeyProof.verify(challenge, proof))
        assertFalse(KeyProof.verify(KeyProof.newChallenge(), proof))
    }
}
