package io.gatekeeper

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.SettingsManager

/**
 * Хранилище настроек в памяти для JVM-тестов: [LocalStorageManager] и [SettingsManager]
 * получают его через контекст-заглушку, без устройства и без Robolectric.
 *
 * Годится для логики, которая читает и пишет настройки. Все, что уходит в систему
 * (DPM, PackageManager, startActivity), на заглушке не работает -- это проверяют
 * инструментальные тесты.
 */
object TestStorage {
    /** Свежее пустое хранилище; вызывать в @Before, синглтоны общие на весь прогон. */
    fun install(): InMemoryPreferences {
        val prefs = InMemoryPreferences()
        val context = PrefsContext(prefs)
        LocalStorageManager.initialize(context)
        SettingsManager.initialize(context)
        return prefs
    }

    /** Контекст ровно для [Context.getSharedPreferences]; остальное -- заглушки android.jar. */
    private class PrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs

        override fun getPackageName(): String = "io.gatekeeper"
    }
}

/**
 * [SharedPreferences] в памяти с семантикой фреймворка там, где на нее опирается код:
 * чтение значения другого типа бросает [ClassCastException], правки одного редактора
 * применяются по порядку вызовов, `commit` и `apply` одинаково синхронны.
 */
class InMemoryPreferences : SharedPreferences {
    private val values = LinkedHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String?, defValue: String?): String? = values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as Set<String>?)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = values[key] as Int? ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as Long? ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as Float? ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        /** Как во фреймворке: удаление -- маркер в той же карте, последняя правка ключа побеждает. */
        private val pending = LinkedHashMap<String, Any>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            pending[requireNotNull(key)] = value ?: REMOVED
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())

        override fun putInt(key: String?, value: Int) = put(key, value)

        override fun putLong(key: String?, value: Long) = put(key, value)

        override fun putFloat(key: String?, value: Float) = put(key, value)

        override fun putBoolean(key: String?, value: Boolean) = put(key, value)

        override fun remove(key: String?) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            for ((key, value) in pending) {
                if (value === REMOVED) values.remove(key) else values[key] = value
            }
            pending.clear()
            clearAll = false
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}
