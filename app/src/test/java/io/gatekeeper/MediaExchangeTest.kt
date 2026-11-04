package io.gatekeeper

import io.gatekeeper.util.BackupPayload
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.MediaExchangeItem
import io.gatekeeper.util.MediaExchangeLedger
import io.gatekeeper.util.MediaExchangePolicy
import io.gatekeeper.util.MediaExchangeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class MediaExchangeTest {
    @Before fun setup() { TestStorage.install() }

    @Test fun newInstallEnablesBothDirectionsWithoutExportingEveryFolder() {
        val config = MediaExchangeSettings.current()
        assertTrue(config.enabled && config.screenshots && config.camera)
        assertTrue(MediaExchangePolicy.allowed(true, "Pictures/Screenshots/", "image/png", true, true))
        assertTrue(MediaExchangePolicy.allowed(false, "DCIM/Camera/", "image/jpeg", true, true))
        assertFalse(MediaExchangePolicy.allowed(true, "DCIM/Camera/", "image/jpeg", true, true))
        assertFalse(MediaExchangePolicy.allowed(false, "Pictures/Screenshots/", "image/png", true, true))
        assertFalse(MediaExchangePolicy.allowed(false, "DCIM/Camera/../Private/", "image/jpeg", true, true))
        assertFalse(MediaExchangePolicy.allowed(
            false, "Pictures/Gatekeeper/Personal Camera/", "image/jpeg", true, true,
        ))
        assertFalse(MediaExchangePolicy.allowed(false, "DCIM/Camera/", "video/mp4", true, true))
    }

    @Test fun explicitLegacyOffAndExplicitNewOffSurviveUpgrade() {
        LocalStorageManager.getInstance().setBooleanNow(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED, false)
        assertFalse(MediaExchangeSettings.current().enabled)
        MediaExchangeSettings.set(MediaExchangePolicy.ENABLED, true)
        assertTrue(MediaExchangeSettings.current().enabled)
        MediaExchangeSettings.set(MediaExchangePolicy.ENABLED, false)
        LocalStorageManager.getInstance().setBooleanNow(LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED, true)
        assertFalse(MediaExchangeSettings.current().enabled)
    }

    @Test fun disablingOneDirectionDoesNotResetTheOtherDeliveryHistory() {
        val initial = MediaExchangeSettings.current()
        MediaExchangeSettings.set(MediaExchangePolicy.CAMERA, false)
        assertEquals(initial.screenshotEpoch, MediaExchangeSettings.current().screenshotEpoch)
        MediaExchangeSettings.set(MediaExchangePolicy.CAMERA, true)
        val after = MediaExchangeSettings.current()
        assertEquals(initial.screenshotEpoch, after.screenshotEpoch)
        assertEquals(initial.cameraEpoch + 1, after.cameraEpoch)
        assertFalse(MediaExchangePolicy.allowed(false, "DCIM/Camera", "image/jpeg", true, false))
    }

    @Test fun offOnStartsANewBaselineAndInvalidatesInFlightCopies() {
        val before = MediaExchangeSettings.current().wire()
        MediaExchangeSettings.set(MediaExchangePolicy.ENABLED, false)
        assertNotEquals(before, MediaExchangeSettings.current().wire())
        MediaExchangeSettings.set(MediaExchangePolicy.ENABLED, true)
        val enabled = MediaExchangeSettings.current()
        assertEquals(1L, enabled.cameraEpoch)
        assertEquals(1L, enabled.screenshotEpoch)
        assertEquals(enabled, MediaExchangeSettings.parse(enabled.wire()))
        assertNotEquals(before, enabled.wire())
    }

    @Test fun sameNamesAndTimesNeverDefineDeliveryIdentity() {
        val source = "installation:volume:epoch"
        assertEquals(MediaExchangeLedger.token(source, 1), MediaExchangeLedger.token(source, 1))
        assertNotEquals(MediaExchangeLedger.token(source, 1), MediaExchangeLedger.token(source, 2))
        assertNotEquals(MediaExchangeLedger.token(source, 1), MediaExchangeLedger.token("another-profile", 1))
        assertNotEquals(
            MediaExchangeLedger.token(source, 1), MediaExchangeLedger.token("installation:volume:next-epoch", 1),
        )
    }

    @Test fun itemRoundTripPreservesNonAsciiNamesAndPendingState() {
        val item = MediaExchangeItem(3, "Снимок | фото.png", "image/png", 1024, 100, 101, false)
        assertEquals(item, MediaExchangeItem.parse(item.wire()))
        assertNotEquals(item.version, item.copy(size = 2048).version)
        assertNotEquals(item.version, item.copy(modified = 102).version)
    }

    @Test fun settingsBackupPreservesOptOutButNeverExportsDeliveryHistory() {
        val config = MediaExchangeSettings.current()
        MediaExchangeSettings.set(MediaExchangePolicy.ENABLED, false)
        val storage = LocalStorageManager.getInstance()
        val snapshot = storage.snapshotSettings(BackupPayload.EXPORTABLE_SETTINGS)
        assertEquals(false, snapshot[MediaExchangePolicy.ENABLED]?.value)
        assertFalse(snapshot.containsKey(MediaExchangePolicy.REVISION))
        assertFalse(snapshot.containsKey("media_exchange_install_id"))
        assertTrue(config.enabled)
    }

    @Test fun retryDelayIsBounded() {
        assertEquals(1000L, MediaExchangeLedger.retryDelay(0))
        assertEquals(2000L, MediaExchangeLedger.retryDelay(1))
        assertEquals(256000L, MediaExchangeLedger.retryDelay(Int.MAX_VALUE))
    }
}
