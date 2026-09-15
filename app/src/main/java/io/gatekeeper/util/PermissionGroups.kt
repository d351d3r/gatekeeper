package io.gatekeeper.util

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.gatekeeper.R

/**
 * Группа опасных (runtime) разрешений с человеческим названием и глифом. Пер-аппный
 * отзыв работает по каждому разрешению группы через
 * DevicePolicyManager.setPermissionGrantState. Строки-константы, а не
 * Manifest.permission: часть прав появилась в поздних API, а фильтруем мы по тому, что
 * приложение реально объявило, так что версия не важна. iconRes -- значок группы под
 * именем приложения в списке.
 */
data class PermGroup(
    val key: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    val permissions: List<String>,
)

object PermissionGroups {
    private const val P = "android.permission."

    val ALL: List<PermGroup> = listOf(
        PermGroup(
            "location", R.string.perm_group_location, R.drawable.ic_perm_location,
            listOf(
                P + "ACCESS_FINE_LOCATION",
                P + "ACCESS_COARSE_LOCATION",
                P + "ACCESS_BACKGROUND_LOCATION",
            ),
        ),
        PermGroup("camera", R.string.perm_group_camera, R.drawable.ic_perm_camera, listOf(P + "CAMERA")),
        PermGroup(
            "microphone", R.string.perm_group_microphone, R.drawable.ic_perm_mic,
            listOf(P + "RECORD_AUDIO"),
        ),
        PermGroup(
            "contacts", R.string.perm_group_contacts, R.drawable.ic_perm_contacts,
            listOf(P + "READ_CONTACTS", P + "WRITE_CONTACTS", P + "GET_ACCOUNTS"),
        ),
        PermGroup(
            "phone", R.string.perm_group_phone, R.drawable.ic_perm_phone,
            listOf(
                P + "READ_PHONE_STATE",
                P + "READ_PHONE_NUMBERS",
                P + "CALL_PHONE",
                P + "READ_CALL_LOG",
                P + "WRITE_CALL_LOG",
                P + "ANSWER_PHONE_CALLS",
            ),
        ),
        PermGroup(
            "sms", R.string.perm_group_sms, R.drawable.ic_perm_sms,
            listOf(
                P + "SEND_SMS",
                P + "RECEIVE_SMS",
                P + "READ_SMS",
                P + "RECEIVE_MMS",
                P + "RECEIVE_WAP_PUSH",
            ),
        ),
        PermGroup(
            "calendar", R.string.perm_group_calendar, R.drawable.ic_perm_calendar,
            listOf(P + "READ_CALENDAR", P + "WRITE_CALENDAR"),
        ),
        PermGroup(
            "sensors", R.string.perm_group_sensors, R.drawable.ic_perm_sensors,
            listOf(P + "BODY_SENSORS", P + "BODY_SENSORS_BACKGROUND"),
        ),
        PermGroup(
            "nearby", R.string.perm_group_nearby, R.drawable.ic_perm_nearby,
            listOf(
                P + "BLUETOOTH_SCAN",
                P + "BLUETOOTH_CONNECT",
                P + "BLUETOOTH_ADVERTISE",
                P + "NEARBY_WIFI_DEVICES",
            ),
        ),
        PermGroup(
            "storage", R.string.perm_group_storage, R.drawable.ic_perm_storage,
            listOf(
                P + "READ_EXTERNAL_STORAGE",
                P + "WRITE_EXTERNAL_STORAGE",
                P + "READ_MEDIA_IMAGES",
                P + "READ_MEDIA_VIDEO",
                P + "READ_MEDIA_AUDIO",
                P + "ACCESS_MEDIA_LOCATION",
            ),
        ),
        PermGroup(
            "activity", R.string.perm_group_activity, R.drawable.ic_perm_activity,
            listOf(P + "ACTIVITY_RECOGNITION"),
        ),
        PermGroup(
            "notifications", R.string.perm_group_notifications, R.drawable.ic_perm_notifications,
            listOf(P + "POST_NOTIFICATIONS"),
        ),
    )

    /** Все разрешения, которыми мы умеем управлять пер-аппно, одним множеством. */
    fun controllable(): Set<String> = ALL.flatMapTo(HashSet()) { it.permissions }

    /** Группы, у которых приложение объявило хотя бы одно разрешение; группа сужается
     *  до реально объявленных прав. */
    fun groupsFor(declared: Set<String>): List<PermGroup> =
        ALL.mapNotNull { group ->
            val present = group.permissions.filter { it in declared }
            if (present.isEmpty()) null else group.copy(permissions = present)
        }
}
