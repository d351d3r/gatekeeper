package io.gatekeeper.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * Формат бэкапа настроек и списков приложений (C4, 4PDA #947, #948, #957).
 * Данные приложений рабочего профиля без root недоступны -- экспортируем
 * честные границы: настройки Gatekeeper, список пакетов обоих профилей и
 * список автозаморозки. Секреты (ключ авторизации и др.) намеренно не входят
 * в [EXPORTABLE_SETTINGS].
 *
 * Чистая сериализация/парсинг без Android-зависимостей, чтобы гонять
 * round-trip в обычных unit-тестах.
 */
object BackupPayload {
    const val FORMAT = "gatekeeper-backup"
    const val VERSION = 1

    const val TYPE_BOOLEAN = "boolean"
    const val TYPE_INT = "int"
    const val TYPE_LONG = "long"
    const val TYPE_STRING = "string"

    /**
     * Типизированное значение настройки. Тип храним явно: SharedPreferences
     * различает Int и Long, и молчаливая потеря типа ломала чтение.
     */
    data class SettingValue(val type: String, val value: Any)

    data class Payload(
        val settings: Map<String, SettingValue>,
        val mainApps: List<String>,
        val workApps: List<String>,
        val autoFreezeWork: List<String>,
    )

    /**
     * Белый список экспортируемых настроек. Сюда не входят: ключ
     * авторизации и её состояние, одноразовые флаги миграций и подсказок,
     * device-specific реестры ярлыков и водяные знаки -- всё это либо секрет,
     * либо мусор на новом устройстве.
     */
    val EXPORTABLE_SETTINGS: Set<String> = setOf(
        LocalStorageManager.PREF_CROSS_PROFILE_FILE_CHOOSER,
        LocalStorageManager.PREF_DYNAMIC_COLORS,
        LocalStorageManager.PREF_AUTO_FREEZE_SERVICE,
        LocalStorageManager.PREF_DONT_FREEZE_FOREGROUND,
        LocalStorageManager.PREF_AUTO_FREEZE_DELAY,
        LocalStorageManager.PREF_BLOCK_CONTACTS_SEARCHING,
        LocalStorageManager.PREF_PAYMENT_STUB,
        LocalStorageManager.PREF_ANTI_SPY_VPN_WATCH_ENABLED,
        LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_VPN,
        LocalStorageManager.PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK,
        LocalStorageManager.PREF_ANTI_SPY_FREEZE_SCOPE,
        LocalStorageManager.PREF_ANTI_SPY_NOTIFY_ONLY,
        LocalStorageManager.PREF_ANTI_SPY_FREEZE_DELAY,
        LocalStorageManager.PREF_MEDIA_MIRROR_ENABLED,
        LocalStorageManager.PREF_CROSS_PROFILE_LINK_RULES,
    )

    fun serialize(payload: Payload): String {
        val settings = JSONObject()
        for ((key, sv) in payload.settings) {
            val entry = JSONObject()
            entry.put("type", sv.type)
            when (sv.type) {
                TYPE_BOOLEAN -> entry.put("value", sv.value as Boolean)
                TYPE_LONG -> entry.put("value", sv.value as Long)
                TYPE_STRING -> entry.put("value", sv.value as String)
                else -> entry.put("value", sv.value as Int)
            }
            settings.put(key, entry)
        }
        val root = JSONObject()
        root.put("format", FORMAT)
        root.put("version", VERSION)
        root.put("settings", settings)
        root.put("mainApps", JSONArray(payload.mainApps))
        root.put("workApps", JSONArray(payload.workApps))
        root.put("autoFreezeWork", JSONArray(payload.autoFreezeWork))
        return root.toString()
    }

    /** null -- файл не бэкап Gatekeeper или повреждён. */
    fun parse(json: String): Payload? {
        return try {
            val root = JSONObject(json)
            if (root.getString("format") != FORMAT) return null
            if (root.getInt("version") > VERSION) return null
        val settings = LinkedHashMap<String, SettingValue>()
        val settingsObj = root.getJSONObject("settings")
        for (key in settingsObj.keys()) {
            val entry = settingsObj.getJSONObject(key)
            val type = entry.getString("type")
            val value: Any = when (type) {
                TYPE_BOOLEAN -> entry.getBoolean("value")
                TYPE_INT -> entry.getInt("value")
                TYPE_LONG -> entry.getLong("value")
                TYPE_STRING -> entry.getString("value")
                else -> continue
            }
            settings[key] = SettingValue(type, value)
        }
        Payload(
            settings = settings,
            mainApps = readList(root.getJSONArray("mainApps")),
            workApps = readList(root.getJSONArray("workApps")),
            autoFreezeWork = readList(root.getJSONArray("autoFreezeWork")),
        )
        } catch (_: Exception) {
            null
        }
    }

    private fun readList(array: JSONArray): List<String> =
        (0 until array.length()).map { array.getString(it) }
}
