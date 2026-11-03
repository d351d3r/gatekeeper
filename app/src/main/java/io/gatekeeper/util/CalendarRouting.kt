package io.gatekeeper.util

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import io.gatekeeper.services.CalendarRoutingJobService

data class CalendarRoutingTarget(
    val id: Long,
    val accountName: String,
    val displayName: String,
)

@Suppress("TooManyFunctions")
object CalendarRouting {
    private const val TAG = "CalendarRouting"
    private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    private const val OBSERVER_JOB_ID = 0x43414c01
    private const val INITIAL_JOB_ID = 0x43414c02
    private const val TARGET_FIELD_COUNT = 3
    private const val CONTENT_UPDATE_DELAY_MS = 1_000L
    private const val CONTENT_MAX_DELAY_MS = 5_000L

    fun encodeTarget(target: CalendarRoutingTarget): String =
        "${target.id}|${target.accountName}|${target.displayName}"

    fun decodeTarget(encoded: String): CalendarRoutingTarget? {
        val parts = encoded.split('|', limit = TARGET_FIELD_COUNT)
        val id = parts.firstOrNull()?.toLongOrNull()
        return if (parts.size == TARGET_FIELD_COUNT && id != null) {
            CalendarRoutingTarget(id, parts[1], parts[2])
        } else {
            null
        }
    }

    fun listWritableGoogleCalendars(context: Context): List<CalendarRoutingTarget> {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.ACCOUNT_TYPE} = ? AND " +
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(
                    GOOGLE_ACCOUNT_TYPE,
                    CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString(),
                ),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                    "${CalendarContract.Calendars.ACCOUNT_NAME}, " +
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            CalendarRoutingTarget(
                                cursor.getLong(0),
                                cursor.getString(1).orEmpty(),
                                cursor.getString(2).orEmpty(),
                            )
                        )
                    }
                }
            }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot list calendars", e)
            emptyList()
        }
    }

    fun state(): Pair<Boolean, Long?> {
        val storage = LocalStorageManager.getInstance()
        val id = storage.getLong(LocalStorageManager.PREF_LOCAL_CALENDAR_TARGET, -1L)
            .takeIf { it >= 0L }
        return storage.getBoolean(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING) to id
    }

    /**
     * Системная сторона маршрутизации: права календаря, провайдер календаря, задачи
     * JobScheduler. Порядок вызовов и откат при сбое -- в [configure] и
     * [moveLocalEvents], они покрыты JVM-тестами с заглушкой этого интерфейса.
     */
    internal interface Platform {
        fun setCalendarPermissions(grant: Boolean): Boolean
        fun writableGoogleCalendars(): List<CalendarRoutingTarget>
        fun localCalendarIds(): List<Long>
        fun moveEvents(selection: String, selectionArgs: Array<String>, targetId: Long): Int
        fun scheduleObserver(): Boolean
        fun scheduleInitialRun(): Boolean
        fun cancelJobs()
    }

    private class AndroidPlatform(
        private val context: Context,
        private val manager: DevicePolicyManager? = null,
        private val admin: ComponentName? = null,
    ) : Platform {
        override fun setCalendarPermissions(grant: Boolean): Boolean =
            manager != null && admin != null && setCalendarPermissions(context, manager, admin, grant)

        override fun writableGoogleCalendars() = listWritableGoogleCalendars(context)

        override fun localCalendarIds() = localCalendarIds(context)

        override fun moveEvents(selection: String, selectionArgs: Array<String>, targetId: Long) =
            updateEventCalendar(context, selection, selectionArgs, targetId)

        override fun scheduleObserver() = scheduleObserver(context)

        override fun scheduleInitialRun() = scheduleInitialRun(context)

        override fun cancelJobs() = cancel(context)
    }

    fun configure(
        context: Context,
        manager: DevicePolicyManager,
        admin: ComponentName,
        targetId: Long,
        enabled: Boolean,
    ): String? = configure(AndroidPlatform(context, manager, admin), targetId, enabled)

    /**
     * Включение не оставляет полусостояния: при любом сбое права календаря отзываются,
     * флаг маршрутизации снят, задачи отменены. Выключение помнит выбранный календарь.
     */
    @Suppress("ReturnCount")
    internal fun configure(platform: Platform, targetId: Long, enabled: Boolean): String? {
        val storage = LocalStorageManager.getInstance()
        if (!enabled) {
            if (targetId >= 0L) {
                storage.setLong(LocalStorageManager.PREF_LOCAL_CALENDAR_TARGET, targetId)
            }
            storage.setBooleanNow(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING, false)
            platform.cancelJobs()
            platform.setCalendarPermissions(false)
            return null
        }

        if (!platform.setCalendarPermissions(true)) {
            return ERROR_PERMISSION
        }
        if (platform.writableGoogleCalendars().none { it.id == targetId }) {
            platform.setCalendarPermissions(false)
            return ERROR_TARGET_NOT_FOUND
        }

        storage.setLong(LocalStorageManager.PREF_LOCAL_CALENDAR_TARGET, targetId)
        storage.setBooleanNow(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING, true)
        if (!platform.scheduleObserver() || !platform.scheduleInitialRun()) {
            storage.setBooleanNow(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING, false)
            platform.cancelJobs()
            platform.setCalendarPermissions(false)
            return ERROR_SCHEDULER
        }
        return null
    }

    fun scheduleIfEnabled(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isProfileOwnerApp(context.packageName) != true) {
            return
        }
        if (!LocalStorageManager.getInstance()
                .getBoolean(LocalStorageManager.PREF_LOCAL_CALENDAR_ROUTING)
        ) {
            cancel(context)
            return
        }
        scheduleObserver(context)
    }

    fun scheduleObserver(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val service = ComponentName(context, CalendarRoutingJobService::class.java)
        val job = JobInfo.Builder(OBSERVER_JOB_ID, service)
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.Events.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.Calendars.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .setTriggerContentUpdateDelay(CONTENT_UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(CONTENT_MAX_DELAY_MS)
            .build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    private fun scheduleInitialRun(context: Context): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(
            INITIAL_JOB_ID,
            ComponentName(context, CalendarRoutingJobService::class.java),
        ).setMinimumLatency(0L).build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }

    fun moveLocalEvents(context: Context): Int = moveLocalEvents(AndroidPlatform(context))

    /** События локальных календарей уезжают в выбранный Google-календарь, пока он доступен для записи. */
    @Suppress("ReturnCount")
    internal fun moveLocalEvents(platform: Platform): Int {
        val (enabled, targetId) = state()
        if (!enabled || targetId == null) return 0
        if (platform.writableGoogleCalendars().none { it.id == targetId }) {
            Log.w(TAG, "selected Google calendar is no longer writable: $targetId")
            return 0
        }
        val (selection, args) = moveSelection(platform.localCalendarIds()) ?: return 0
        return platform.moveEvents(selection, args, targetId)
    }

    /**
     * Выборка событий локальных календарей; удаленные (DELETED = 1) не трогаем, иначе
     * перенос воскресил бы их в Google-календаре. null -- локальных календарей нет.
     */
    internal fun moveSelection(localCalendarIds: List<Long>): Pair<String, Array<String>>? {
        if (localCalendarIds.isEmpty()) return null
        val placeholders = localCalendarIds.joinToString(",") { "?" }
        val selection = "${CalendarContract.Events.CALENDAR_ID} IN ($placeholders) AND " +
            "${CalendarContract.Events.DELETED} != 1"
        return selection to localCalendarIds.map(Long::toString).toTypedArray()
    }

    private fun updateEventCalendar(
        context: Context,
        selection: String,
        selectionArgs: Array<String>,
        targetId: Long,
    ): Int {
        val values = ContentValues(1).apply {
            put(CalendarContract.Events.CALENDAR_ID, targetId)
        }
        return try {
            context.contentResolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                selection,
                selectionArgs,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "cannot move local calendar events to $targetId", e)
            0
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "cannot move local calendar events to $targetId", e)
            0
        } catch (e: android.database.sqlite.SQLiteException) {
            Log.e(TAG, "cannot move local calendar events to $targetId", e)
            0
        }
    }

    private fun localCalendarIds(context: Context): List<Long> = try {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(CalendarContract.ACCOUNT_TYPE_LOCAL),
            null,
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getLong(0))
            }
        }.orEmpty()
    } catch (e: SecurityException) {
        Log.e(TAG, "cannot list local calendars", e)
        emptyList()
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "cannot list local calendars", e)
        emptyList()
    } catch (e: android.database.sqlite.SQLiteException) {
        Log.e(TAG, "cannot list local calendars", e)
        emptyList()
    }

    private fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.apply {
            cancel(OBSERVER_JOB_ID)
            cancel(INITIAL_JOB_ID)
        }
    }

    private fun setCalendarPermissions(
        context: Context,
        manager: DevicePolicyManager,
        admin: ComponentName,
        grant: Boolean,
    ): Boolean {
        val state = if (grant) {
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        } else {
            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        }
        val permissions = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        var success = true
        for (permission in permissions) {
            success = try {
                manager.setPermissionGrantState(admin, context.packageName, permission, state) && success
            } catch (e: SecurityException) {
                Log.e(TAG, "cannot change $permission grant state", e)
                false
            }
        }
        if (grant && !success) {
            for (permission in permissions) {
                try {
                    manager.setPermissionGrantState(
                        admin,
                        context.packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT,
                    )
                } catch (_: SecurityException) {
                }
            }
        }
        return success
    }

    const val ERROR_TARGET_NOT_FOUND = "target_not_found"
    const val ERROR_PERMISSION = "permission"
    const val ERROR_SCHEDULER = "scheduler"
}
