package io.gatekeeper

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.gatekeeper.receivers.GatekeeperDeviceAdminReceiver
import io.gatekeeper.util.CalendarRouting
import io.gatekeeper.util.LocalStorageManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Перенос событий локального календаря рабочего профиля в Google-календарь на живом
 * провайдере календаря. Порядок шагов и откат покрыты JVM-тестом
 * CalendarRoutingConfigureTest; здесь -- что выборка и запись принимаются провайдером.
 * Только в рабочем профиле: права календаря выдает себе владелец профиля.
 */
@RunWith(AndroidJUnit4::class)
class CalendarRoutingDeviceTest {
    private val context = TestProfiles.targetContext
    private val resolver = context.contentResolver
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private val createdCalendars = ArrayList<Uri>()
    private var previousRouting = false
    private var previousTarget = -1L

    @Before
    fun setUp() {
        assumeTrue("нужен прогон в рабочем профиле", TestProfiles.runningInWorkProfile)
        dpm = context.getSystemService(DevicePolicyManager::class.java)
        admin = ComponentName(context, GatekeeperDeviceAdminReceiver::class.java)
        val storage = LocalStorageManager.getInstance()
        previousRouting = storage.getBoolean(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING)
        previousTarget = storage.getLong(LocalStorageManager.PREF_LOCAL_CALENDAR_TARGET, -1L)
        for (permission in listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) {
            dpm.setPermissionGrantState(
                admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    @After
    fun tearDown() {
        if (!TestProfiles.runningInWorkProfile) return
        // Календарь удаляется вместе со своими событиями, в том числе перенесенными.
        createdCalendars.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        CalendarRouting.configure(context, dpm, admin, -1L, enabled = false)
        val storage = LocalStorageManager.getInstance()
        storage.setBooleanNow(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING, previousRouting)
        storage.setLong(LocalStorageManager.PREF_LOCAL_CALENDAR_TARGET, previousTarget)
    }

    @Test
    fun localEventMovesToTheSelectedGoogleCalendar() {
        val google = insertCalendar("gk.routing.test@gmail.com", GOOGLE_ACCOUNT_TYPE)
        val local = insertCalendar("gk.routing.local", CalendarContract.ACCOUNT_TYPE_LOCAL)
        val event = insertEvent(local)

        assertTrue(CalendarRouting.listWritableGoogleCalendars(context).any { it.id == google })
        assertNull(CalendarRouting.configure(context, dpm, admin, google, enabled = true))
        CalendarRouting.moveLocalEvents(context)

        assertEquals(google, calendarOf(event))
    }

    /** Удаленное событие остается в локальном календаре: перенос не воскрешает его. */
    @Test
    fun deletedLocalEventIsNotMoved() {
        val google = insertCalendar("gk.routing.test@gmail.com", GOOGLE_ACCOUNT_TYPE)
        val local = insertCalendar("gk.routing.local", CalendarContract.ACCOUNT_TYPE_LOCAL)
        val event = insertEvent(local)
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, event), null, null)

        assertNull(CalendarRouting.configure(context, dpm, admin, google, enabled = true))
        CalendarRouting.moveLocalEvents(context)

        assertEquals(local, calendarOf(event))
    }

    private fun asSyncAdapter(uri: Uri, accountName: String, accountType: String): Uri =
        uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, accountName)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, accountType)
            .build()

    private fun insertCalendar(accountName: String, accountType: String): Long {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, accountName)
            put(Calendars.ACCOUNT_TYPE, accountType)
            put(Calendars.NAME, "GK routing $accountType")
            put(Calendars.CALENDAR_DISPLAY_NAME, "GK routing $accountType")
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, accountName)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
        }
        val row = checkNotNull(resolver.insert(asSyncAdapter(Calendars.CONTENT_URI, accountName, accountType), values)) {
            "календарь $accountType не создан"
        }
        val id = ContentUris.parseId(row)
        createdCalendars += asSyncAdapter(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), accountName, accountType)
        return id
    }

    private fun insertEvent(calendarId: Long): Long {
        val start = System.currentTimeMillis()
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, "GK routing $start")
            put(Events.DTSTART, start)
            put(Events.DTEND, start + HOUR_MS)
            put(Events.EVENT_TIMEZONE, "UTC")
        }
        return ContentUris.parseId(checkNotNull(resolver.insert(Events.CONTENT_URI, values)) { "событие не создано" })
    }

    private fun calendarOf(eventId: Long): Long = checkNotNull(
        resolver.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.CALENDAR_ID),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    ) { "событие $eventId не найдено" }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        const val HOUR_MS = 3_600_000L
    }
}
