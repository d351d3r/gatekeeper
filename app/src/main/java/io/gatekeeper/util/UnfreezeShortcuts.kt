package io.gatekeeper.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import android.util.Log
import io.gatekeeper.ui.DummyActivity

/**
 * Реестр и снятие ярлыков «Разморозить и запустить»: персист в
 * PREF_UNFREEZE_SHORTCUT_REGISTRY, снятие через ShortcutManager (O+) и
 * legacy-бродкаст лаунчерам. Вынесено из Utility.
 */
private const val FIELD_SEP = "\u001f"
private const val RECORD_MIN_FIELDS = 3
private const val RECORD_LINKED_INDEX = 3

object UnfreezeShortcuts {
    private const val TAG = "UnfreezeShortcuts"

    fun buildLaunchIntent(
        context: Context,
        packageName: String,
        linkedPackages: String? = null
    ): Intent {
        return Intent(DummyActivity.PUBLIC_UNFREEZE_AND_LAUNCH).apply {
            component = ComponentName(context, DummyActivity::class.java)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("packageName", packageName)
            if (linkedPackages != null) {
                putExtra("linkedPackages", linkedPackages)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    fun shortcutId(packageName: String, linkedPackages: String? = null): String {
        var id = "gatekeeper-$packageName"
        if (linkedPackages != null) {
            id += linkedPackages.hashCode()
        }
        return id
    }

    fun register(
        packageName: String,
        id: String,
        label: String,
        linkedPackages: String? = null
    ) {
        val storage = LocalStorageManager.getInstance()
        val list = storage.getStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY)
            .filterNot { entry ->
                val parts = entry.split(FIELD_SEP)
                parts.size >= 2 && parts[1] == id
            }
            .toMutableList()
        val fields = buildList {
            add(packageName)
            add(id)
            add(label)
            if (!linkedPackages.isNullOrEmpty()) {
                add(linkedPackages)
            }
        }
        list.add(fields.joinToString(FIELD_SEP))
        storage.setStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY, list.toTypedArray())
    }

    private fun recordsForPackage(packageName: String): List<Record> {
        return LocalStorageManager.getInstance()
            .getStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY)
            .mapNotNull(::parse)
            .filter { record ->
                record.packageName == packageName ||
                        record.linkedPackages?.split(",")?.contains(packageName) == true
            }
    }

    private fun sendUninstallBroadcast(
        context: Context,
        launchIntent: Intent,
        label: String?
    ) {
        val shortcutIntent = Intent(launchIntent).apply {
            if (categories?.contains(Intent.CATEGORY_DEFAULT) != true) {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        }
        val remove = Intent("com.android.launcher.action.UNINSTALL_SHORTCUT").apply {
            putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent)
            if (label != null) {
                putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            }
        }
        context.sendBroadcast(remove)
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        for (resolveInfo in context.packageManager.queryIntentActivities(
                homeIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )) {
            context.sendBroadcast(Intent(remove).setPackage(resolveInfo.activityInfo.packageName))
        }
    }

    fun removeLauncherShortcuts(context: Context, packageName: String) {
        val idsToDisable = LinkedHashSet<String>()
        idsToDisable.add(shortcutId(packageName))

        val launchIntents = LinkedHashMap<String, Intent>()
        val labels = HashMap<String, String?>()

        for (record in recordsForPackage(packageName)) {
            idsToDisable.add(record.id)
            val intent = buildLaunchIntent(
                context,
                record.packageName,
                record.linkedPackages
            )
            launchIntents[intent.toUri(0)] = intent
            labels[intent.toUri(0)] = record.label
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            collectShortcutIds(context, packageName, idsToDisable, launchIntents, labels)
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)
            try {
                if (idsToDisable.isNotEmpty()) {
                    shortcutManager.disableShortcuts(ArrayList(idsToDisable))
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "disableShortcuts failed for $packageName", e)
            }
        }

        val baseIntent = buildLaunchIntent(context, packageName)
        launchIntents.putIfAbsent(baseIntent.toUri(0), baseIntent)

        for ((key, intent) in launchIntents) {
            sendUninstallBroadcast(context, intent, labels[key])
        }

        unregister(packageName)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun collectShortcutIds(
        context: Context,
        packageName: String,
        idsToDisable: MutableSet<String>,
        launchIntents: MutableMap<String, Intent>,
        labels: MutableMap<String, String?>
    ) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        for (info in shortcutManager.pinnedShortcuts) {
            collectShortcutInfo(info, packageName, idsToDisable, launchIntents, labels)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                val query = LauncherApps.ShortcutQuery().apply {
                    setPackage(context.packageName)
                    setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or
                                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC
                    )
                }
                val shortcuts = launcherApps.getShortcuts(query, Process.myUserHandle()) ?: emptyList()
                for (info in shortcuts) {
                    collectShortcutInfo(info, packageName, idsToDisable, launchIntents, labels)
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "LauncherApps shortcut query failed for $packageName", e)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun collectShortcutInfo(
        info: ShortcutInfo,
        packageName: String,
        idsToDisable: MutableSet<String>,
        launchIntents: MutableMap<String, Intent>,
        labels: MutableMap<String, String?>
    ) {
        val matchesId = info.id == shortcutId(packageName) ||
                info.id.startsWith("gatekeeper-$packageName")
        if (!matchesId && !targetsPackage(info.intent, packageName)) {
            return
        }
        idsToDisable.add(info.id)
        val intent = info.intent ?: return
        launchIntents[intent.toUri(0)] = intent
        labels.putIfAbsent(intent.toUri(0), info.shortLabel?.toString())
    }

    fun removeLauncherShortcutsEverywhere(context: Context, packageName: String) {
        removeLauncherShortcuts(context.applicationContext, packageName)
        requestRemoveOnOtherProfile(context.applicationContext, packageName)
    }

    fun requestRemoveOnOtherProfile(context: Context, packageName: String) {
        try {
            val action = if (AntiSpyManager.isWorkProfile(context)) {
                DummyActivity.REMOVE_UNFREEZE_SHORTCUT
            } else {
                DummyActivity.REMOVE_UNFREEZE_SHORTCUT_2
            }
            val intent = Intent(action).apply {
                putExtra("packageName", packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            Utility.transferIntentToProfile(context, intent)
            context.startActivity(intent)
        } catch (_: IllegalStateException) {
        }
    }
}

private data class Record(
    val packageName: String,
    val id: String,
    val label: String,
    val linkedPackages: String?
)

private fun parse(entry: String): Record? {
    val parts = entry.split(FIELD_SEP)
    if (parts.size < RECORD_MIN_FIELDS) {
        return null
    }
    return Record(
        packageName = parts[0],
        id = parts[1],
        label = parts[2],
        linkedPackages = parts.getOrNull(RECORD_LINKED_INDEX)
    )
}

private fun unregister(packageName: String) {
    val storage = LocalStorageManager.getInstance()
    val remaining = storage.getStringList(LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY)
        .filterNot { entry ->
            val record = parse(entry) ?: return@filterNot false
            record.packageName == packageName ||
                    record.linkedPackages?.split(",")?.contains(packageName) == true
        }
    storage.setStringList(
        LocalStorageManager.PREF_UNFREEZE_SHORTCUT_REGISTRY,
        remaining.toTypedArray()
    )
}

private fun targetsPackage(intent: Intent?, packageName: String): Boolean {
    val primary = intent?.getStringExtra("packageName")
    val linked = intent?.getStringExtra("linkedPackages")
    return packageName == primary || linked?.split(",")?.any { it == packageName } == true
}
