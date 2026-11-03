package io.gatekeeper.util

import android.app.admin.DevicePolicyManager
import android.app.admin.PackagePolicy
import android.content.ComponentName
import android.os.Build
import androidx.annotation.RequiresApi

/** Видимость контактов рабочего профиля из личного (вынесено из WorkProfilePolicy). */
object ContactsAccessPolicy {
    /**
     * Поиск по контактам профиля и имя звонящего в личном журнале. С API 34 булевы
     * методы устарели; справочник прямо описывает соответствие: запрет -- пустой
     * allowlist, разрешение -- пустой blocklist.
     */
    fun apply(manager: DevicePolicyManager, adminComponent: ComponentName) {
        val settings = SettingsManager.getInstance()
        val blockSearch = settings.getBlockContactsSearchingEnabled()
        // Парная политика: без нее номер, сохраненный только в профиле, все равно
        // подписывается именем в журнале личного профиля.
        val blockCallerId = settings.getBlockCallerIdEnabled()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.setManagedProfileContactsAccessPolicy(contactsPolicy(blockSearch))
            manager.setManagedProfileCallerIdAccessPolicy(contactsPolicy(blockCallerId))
        } else {
            applyLegacyContactsPolicies(manager, adminComponent, blockSearch, blockCallerId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun contactsPolicy(blocked: Boolean): PackagePolicy =
        PackagePolicy(
            if (blocked) PackagePolicy.PACKAGE_POLICY_ALLOWLIST else PackagePolicy.PACKAGE_POLICY_BLOCKLIST
        )

    @Suppress("DEPRECATION") // до API 34 других методов нет
    private fun applyLegacyContactsPolicies(
        manager: DevicePolicyManager,
        adminComponent: ComponentName,
        blockSearch: Boolean,
        blockCallerId: Boolean,
    ) {
        manager.setCrossProfileContactsSearchDisabled(adminComponent, blockSearch)
        manager.setCrossProfileCallerIdDisabled(adminComponent, blockCallerId)
    }
}
