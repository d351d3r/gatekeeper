package io.gatekeeper.util

import android.content.Context
import android.content.SharedPreferences

class LocalStorageManager private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private var prefs: SharedPreferences = prefs()

    private fun prefs(): SharedPreferences = freshPrefs(appContext)

    fun remove(pref: String) {
        prefs.edit().remove(pref).apply()
    }

    fun contains(pref: String): Boolean = prefs.contains(pref)

    fun getBoolean(pref: String): Boolean = prefs.getBoolean(pref, false)

    fun getBoolean(pref: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(pref, defaultValue)

    fun setBoolean(pref: String, value: Boolean) {
        prefs.edit().putBoolean(pref, value).apply()
    }

    /**
     * Записать до возврата. Нужно там, где значение сразу читает **другой процесс**:
     * `apply()` кладет его в память своего процесса, а на диск пишет потом, и сторож
     * в `:vpnwatch` успевает прочитать старое.
     */
    fun setBooleanNow(pref: String, value: Boolean) {
        prefs.edit().putBoolean(pref, value).commit()
    }

    fun setIntNow(pref: String, value: Int) {
        prefs.edit().putInt(pref, value).commit()
    }

    fun getInt(pref: String): Int = prefs.getInt(pref, Int.MIN_VALUE)

    fun setInt(pref: String, value: Int) {
        prefs.edit().putInt(pref, value).apply()
    }

    fun getString(pref: String): String? = prefs.getString(pref, null)

    fun setString(pref: String, value: String) {
        prefs.edit().putString(pref, value).apply()
    }

    fun getStringList(pref: String): Array<String> =
        prefs.getString(pref, "")!!
            .split(LIST_DIVIDER)
            .filter { it.isNotEmpty() }
            .toTypedArray()

    /** Re-read from disk (needed for the {@code :vpnwatch} process). */
    fun getStringListFresh(pref: String): Array<String> =
        prefs().getString(pref, "")!!
            .split(LIST_DIVIDER)
            .filter { it.isNotEmpty() }
            .toTypedArray()

    fun getBooleanFresh(pref: String, defaultValue: Boolean): Boolean =
        prefs().getBoolean(pref, defaultValue)

    fun getIntFresh(pref: String, defaultValue: Int): Int = prefs().getInt(pref, defaultValue)

    fun setStringList(pref: String, list: Array<String>) {
        prefs.edit().putString(pref, Utility.stringJoin(LIST_DIVIDER, list)).apply()
    }

    fun stringListContains(pref: String, item: String): Boolean =
        getStringList(pref).indexOf(item) >= 0

    fun appendStringList(pref: String, newItem: String) {
        var str = prefs.getString(pref, null)
        str = if (str == null) {
            newItem
        } else {
            str + LIST_DIVIDER + newItem
        }
        prefs.edit().putString(pref, str).apply()
    }

    fun removeFromStringList(pref: String, item: String) {
        val list = ArrayList(getStringList(pref).toList())
        list.removeIf { it == item }
        setStringList(pref, list.toTypedArray())
    }

    companion object {
        const val PREF_IS_SETTING_UP = "is_setting_up"
        const val PREF_HAS_SETUP = "has_setup"
        const val PREF_AUTO_FREEZE_LIST_WORK_PROFILE = "auto_freeze_list_work_profile"
        const val PREF_CROSS_PROFILE_FILE_CHOOSER = "cross_profile_file_chooser"
        const val PREF_DYNAMIC_COLORS = "dynamic_colors"
        const val PREF_AUTH_KEY = "auth_key"
        const val PREF_AUTH_BOOTSTRAPPED = "auth_bootstrapped"
        const val PREF_AUTO_FREEZE_SERVICE = "auto_freeze_service"
        const val PREF_DONT_FREEZE_FOREGROUND = "dont_freeze_foreground"
        const val PREF_AUTO_FREEZE_DELAY = "auto_freeze_delay"
        const val PREF_BLOCK_CONTACTS_SEARCHING = "block_contacts_searching"
        const val PREF_PAYMENT_STUB = "payment_stub"
        const val PREF_ANTI_SPY_BOOT_FREEZE_PENDING = "anti_spy_boot_freeze_pending"
        const val PREF_ANTI_SPY_LAUNCH_VERSION_CODE = "anti_spy_launch_version_code"
        /** Главный переключатель сторожа VPN; по умолчанию выключен. */
        const val PREF_ANTI_SPY_VPN_WATCH_ENABLED = "anti_spy_vpn_watch_enabled"
        const val PREF_ANTI_SPY_FREEZE_ON_VPN = "anti_spy_freeze_on_vpn"
        const val PREF_ANTI_SPY_FREEZE_ON_SCREEN_LOCK = "anti_spy_freeze_on_screen_lock"
        const val PREF_ANTI_SPY_FREEZE_SCOPE = "anti_spy_freeze_scope"
        const val PREF_ANTI_SPY_NOTIFY_ONLY = "anti_spy_notify_only"
        const val PREF_ANTI_SPY_FREEZE_DELAY = "anti_spy_freeze_delay"
        const val PREF_UNFREEZE_SHORTCUT_REGISTRY = "unfreeze_shortcut_registry"
        const val PREF_LEGACY_FROZEN_MIGRATION_DONE = "legacy_frozen_migration_done"
        /** Last seen work-profile package set; used to detect store installs between sessions. */
        const val PREF_KNOWN_WORK_PROFILE_PACKAGES = "known_work_profile_packages"
        /** User removed auto-freeze; do not re-assign until they enable it in the menu. */
        const val PREF_AUTO_FREEZE_OPT_OUT_WORK_PROFILE = "auto_freeze_opt_out_work_profile"
        /** Store installs waiting for cross-profile write to the auto-freeze list. */
        const val PREF_PENDING_STORE_AUTO_FREEZE = "pending_store_auto_freeze"

        private const val LIST_DIVIDER = ","
        private const val PREFS_NAME = "prefs"

        /**
         * Дескриптор с перечитыванием файла. `getSharedPreferences` отдает **закэшированный
         * в процессе** объект, поэтому без `MODE_MULTI_PROCESS` любое «свежее» чтение в
         * `:vpnwatch` возвращает то, что этот процесс видел при первом обращении: выключенный
         * сторож поднимался снова, потому что читал свой старый кэш. Флаг не липкий -- он
         * влияет только на этот вызов и заставляет проверить файл на диске.
         */
        @Suppress("DEPRECATION")
        private fun freshPrefs(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE or Context.MODE_MULTI_PROCESS
            )

        private var instance: LocalStorageManager? = null

        fun initialize(context: Context) {
            instance = LocalStorageManager(context)
        }

        fun getInstance(): LocalStorageManager {
            return instance
                ?: throw IllegalStateException("LocalStorageManager must be initialized at start-up")
        }

        fun readStringListFresh(context: Context, pref: String): Array<String> =
            freshPrefs(context)
                .getString(pref, "")!!
                .split(LIST_DIVIDER)
                .filter { it.isNotEmpty() }
                .toTypedArray()

        fun readBooleanFresh(context: Context, pref: String, defaultValue: Boolean): Boolean =
            freshPrefs(context).getBoolean(pref, defaultValue)

        fun readIntFresh(context: Context, pref: String, defaultValue: Int): Int =
            freshPrefs(context).getInt(pref, defaultValue)
    }
}
