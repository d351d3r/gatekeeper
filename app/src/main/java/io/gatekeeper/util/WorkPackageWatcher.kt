package io.gatekeeper.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Новые приложения профиля попадают в список автозаморозки без манифест-ресивера
 * (F3, воскрешение E-6).
 *
 * `ACTION_PACKAGE_ADDED` манифестному ресиверу на современных targetSdk не
 * доставляется -- прежний механизм из-за этого и откатили. `getChangedPackages`
 * (API 26+) даёт ту же информацию догоном по номеру последовательности: та же
 * модель watermark, что уже работает в MediaMirror.
 *
 * Живёт в рабочем профиле: пакеты профиля видны только изнутри. Сам список
 * хранится в личном профиле, поэтому найденное уезжает туда через реле.
 */
object WorkPackageWatcher {
    private const val TAG = "WorkPackageWatcher"

    fun scan(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val app = context.applicationContext
        val storage = LocalStorageManager.getInstance()
        val stored = storage.getInt(LocalStorageManager.PREF_PACKAGE_CHANGE_SEQUENCE)
            .takeIf { it != Int.MIN_VALUE }
        // null означает «с этого номера ничего не менялось»: номер оставляем как был,
        // следующий вызов спросит с того же места.
        val changed = app.packageManager.getChangedPackages(stored ?: 0) ?: return
        storage.setIntNow(LocalStorageManager.PREF_PACKAGE_CHANGE_SEQUENCE, changed.sequenceNumber)

        if (NewPackagePolicy.isBaselineRun(stored)) {
            rememberAll(app)
            Log.i(TAG, "baseline recorded at ${changed.sequenceNumber}, nothing added")
        } else {
            reportFresh(app, storage, changed.packageNames)
        }
    }

    private fun reportFresh(
        app: Context,
        storage: LocalStorageManager,
        changedPackages: List<String>,
    ) {
        val candidates = changedPackages.map { describe(app, it) }
        val known = storage.getStringList(LocalStorageManager.PREF_KNOWN_WORK_PACKAGES).toSet()
        // Ушедшее из профиля забываем: если приложение поставят снова, это будет
        // установка, а не обновление, и в список автозаморозки оно должно попасть.
        val gone = candidates.filterNot { it.installed }.map { it.packageName }.toSet()
        val fresh = NewPackagePolicy.pickNew(
            candidates,
            app.packageName,
            AntiSpyManager.getAutoFreezeList(app).toSet(),
            known,
        )
        if (fresh.isEmpty() && gone.isEmpty()) {
            return
        }
        storage.setStringList(
            LocalStorageManager.PREF_KNOWN_WORK_PACKAGES,
            (known - gone + fresh).toTypedArray(),
        )
        if (fresh.isEmpty()) {
            return
        }
        Log.i(TAG, "new packages in profile: $fresh")
        val pending = storage.getStringList(LocalStorageManager.PREF_PENDING_NEW_PACKAGES).toSet()
        storage.setStringList(
            LocalStorageManager.PREF_PENDING_NEW_PACKAGES,
            (pending + fresh).toTypedArray(),
        )
    }

    /** Снимок того, что в профиле уже есть: всё это установкой не считается. */
    private fun rememberAll(context: Context) {
        val pm = context.packageManager
        val present = pm.getInstalledApplications(0)
            .map { NewPackagePolicy.Candidate(
                packageName = it.packageName,
                installed = it.flags and ApplicationInfo.FLAG_INSTALLED != 0,
                system = it.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                launchable = pm.getLaunchIntentForPackage(it.packageName) != null,
            ) }
        LocalStorageManager.getInstance().setStringList(
            LocalStorageManager.PREF_KNOWN_WORK_PACKAGES,
            NewPackagePolicy.eligible(present, context.packageName).toTypedArray(),
        )
    }


    private fun describe(context: Context, packageName: String): NewPackagePolicy.Candidate {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
        return NewPackagePolicy.Candidate(
            packageName = packageName,
            installed = info != null && info.flags and ApplicationInfo.FLAG_INSTALLED != 0,
            system = info != null && info.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            launchable = pm.getLaunchIntentForPackage(packageName) != null,
        )
    }
}
