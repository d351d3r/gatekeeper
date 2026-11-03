package io.gatekeeper

import io.gatekeeper.util.CalendarRouting
import io.gatekeeper.util.CalendarRoutingTarget
import io.gatekeeper.util.LocalStorageManager
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CalendarRoutingTest {
    @Test
    fun targetCodecRoundTripsLabelsContainingSeparator() {
        val target = CalendarRoutingTarget(42L, "user@example.com", "Training | Main")
        assertEquals(target, CalendarRouting.decodeTarget(CalendarRouting.encodeTarget(target)))
        assertNull(CalendarRouting.decodeTarget("broken"))
    }

    /** Удаленные события не переносятся: перенос воскресил бы их в Google-календаре. */
    @Test
    fun moveSelectionCoversLocalCalendarsAndSkipsDeletedEvents() {
        val (selection, args) = checkNotNull(CalendarRouting.moveSelection(listOf(3L, 9L)))
        assertEquals("calendar_id IN (?,?) AND deleted != 1", selection)
        assertArrayEquals(arrayOf("3", "9"), args)
        assertNull(CalendarRouting.moveSelection(emptyList()))
    }
}

/**
 * Включение маршрутизации календаря рабочего профиля: при любом сбое не должно
 * остаться полусостояния -- выданных прав календаря, включенного флага, задач.
 */
class CalendarRoutingConfigureTest {
    private val google = CalendarRoutingTarget(7L, "user@gmail.com", "Main")
    private lateinit var platform: FakePlatform

    @Before
    fun setUp() {
        TestStorage.install()
        platform = FakePlatform()
    }

    private class FakePlatform : CalendarRouting.Platform {
        var grantSucceeds = true
        var targets = listOf<CalendarRoutingTarget>()
        var observerScheduled = true
        var initialRunScheduled = true
        var localIds = listOf(1L, 2L)
        var permissionsGranted = false
        var jobsCancelled = 0
        val moves = ArrayList<Pair<List<String>, Long>>()

        override fun setCalendarPermissions(grant: Boolean): Boolean {
            if (grant && !grantSucceeds) return false
            permissionsGranted = grant
            return true
        }

        override fun writableGoogleCalendars() = targets

        override fun localCalendarIds() = localIds

        override fun moveEvents(selection: String, selectionArgs: Array<String>, targetId: Long): Int {
            moves += selectionArgs.toList() to targetId
            return selectionArgs.size
        }

        override fun scheduleObserver() = observerScheduled

        override fun scheduleInitialRun() = initialRunScheduled

        override fun cancelJobs() {
            jobsCancelled++
        }
    }

    @Test
    fun enablingStoresTheTargetAndSchedulesBothJobs() {
        platform.targets = listOf(google)
        assertNull(CalendarRouting.configure(platform, google.id, enabled = true))
        assertEquals(true to google.id, CalendarRouting.state())
        assertTrue(platform.permissionsGranted)
    }

    @Test
    fun refusedPermissionLeavesRoutingOff() {
        platform.grantSucceeds = false
        platform.targets = listOf(google)
        assertEquals(CalendarRouting.ERROR_PERMISSION, CalendarRouting.configure(platform, google.id, enabled = true))
        assertEquals(false to null, CalendarRouting.state())
    }

    @Test
    fun missingTargetRevokesThePermission() {
        assertEquals(
            CalendarRouting.ERROR_TARGET_NOT_FOUND,
            CalendarRouting.configure(platform, google.id, enabled = true),
        )
        assertFalse(platform.permissionsGranted)
        assertEquals(false to null, CalendarRouting.state())
    }

    @Test
    fun schedulerFailureRollsEverythingBack() {
        platform.targets = listOf(google)
        platform.initialRunScheduled = false
        assertEquals(CalendarRouting.ERROR_SCHEDULER, CalendarRouting.configure(platform, google.id, enabled = true))
        assertFalse(CalendarRouting.state().first)
        assertFalse(platform.permissionsGranted)
        assertEquals(1, platform.jobsCancelled)
    }

    /** Выключение помнит выбранный календарь, но отзывает права и задачи. */
    @Test
    fun disablingKeepsTheChoiceAndRevokesEverything() {
        platform.targets = listOf(google)
        CalendarRouting.configure(platform, google.id, enabled = true)

        assertNull(CalendarRouting.configure(platform, -1L, enabled = false))
        assertEquals(false to google.id, CalendarRouting.state())
        assertFalse(platform.permissionsGranted)
        assertEquals(1, platform.jobsCancelled)
    }

    @Test
    fun movesLocalEventsOnlyWhileTheTargetIsWritable() {
        platform.targets = listOf(google)
        CalendarRouting.configure(platform, google.id, enabled = true)
        assertEquals(2, CalendarRouting.moveLocalEvents(platform))
        assertEquals(listOf(listOf("1", "2") to google.id), platform.moves)

        platform.targets = emptyList()
        assertEquals(0, CalendarRouting.moveLocalEvents(platform))
        assertEquals(1, platform.moves.size)
    }

    @Test
    fun nothingMovesWhileRoutingIsOffOrWithoutLocalCalendars() {
        LocalStorageManager.getInstance().setLong(LocalStorageManager.PREF_LOCAL_CALENDAR_TARGET, google.id)
        platform.targets = listOf(google)
        assertEquals(0, CalendarRouting.moveLocalEvents(platform))

        CalendarRouting.configure(platform, google.id, enabled = true)
        platform.localIds = emptyList()
        assertEquals(0, CalendarRouting.moveLocalEvents(platform))
        assertTrue(platform.moves.isEmpty())
    }
}
