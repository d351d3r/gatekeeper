@file:Suppress("DEPRECATION") // LocalBroadcastManager, pre-O shortcuts/notifications, pre-30 cross-profile APIs

package io.gatekeeper.util

import android.annotation.TargetApi
import android.app.AppOpsManager
import android.app.Notification
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.DrawableRes
import androidx.annotation.NonNull
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.ui.DummyActivity
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object Utility {
    private const val TAG = "Utility"

    /** [LocalStorageManager.getStringList] yields `[""]` for an empty list. */
    fun normalizeStringList(list: Array<String>?): Array<String> {
        if (list == null || list.isEmpty()) {
            return emptyArray()
        }
        if (list.size == 1 && list[0].isEmpty()) {
            return emptyArray()
        }
        return list
    }

    /**
     * Foreground delivery: [DummyActivity.FREEZE_ALL_IN_LIST] in the work profile.
     */

    @Throws(ReflectiveOperationException::class)


    fun resolveApplicationLabel(context: Context, packageName: String): CharSequence {
        return try {
            val pm = context.packageManager
            var flags = PackageManager.MATCH_DISABLED_COMPONENTS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                flags = flags or PackageManager.MATCH_UNINSTALLED_PACKAGES
            }
            val info = pm.getApplicationInfo(packageName, flags)
            pm.getApplicationLabel(info)
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    fun isProfileOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isProfileOwnerApp(context.packageName)

    fun stringJoin(delimiter: String, list: Array<String>): String = list.joinToString(delimiter)

    fun transferIntentToProfile(context: Context, intent: Intent) {
        transferIntentToProfileUnsigned(context, intent)
        AuthenticationUtility.signIntent(intent)
    }

    fun transferIntentToProfileUnsigned(context: Context, intent: Intent) {
        val candidates = context.packageManager.queryIntentActivities(intent, 0)
            .map { ProfileForwarder.Candidate(it.activityInfo.packageName, it.activityInfo.name) }
        val forwarder = ProfileForwarder.pickForwarder(candidates)
        if (forwarder == null) {
            // Список кандидатов нужен, чтобы разобрать отказ на устройстве: он покажет и
            // прошивку с нештатным форвардером, и приложение, объявившее наши действия.
            Log.w(
                TAG,
                "no system forwarder for ${intent.action}, candidates: " +
                    candidates.joinToString { "${it.packageName}/${it.className}" }
            )
            throw IllegalStateException("Cannot find an intent in other profile")
        }
        intent.component = ComponentName(forwarder.packageName, forwarder.className)
    }

    /**
     * Реле для отправителей, у которых нет осмысленной реакции на отказ. Строгий выбор
     * форвардера (D4a) сделал отказ штатным исходом: чужое приложение с нашими строками
     * действий теперь не перехватывает интент, а вытесняет реле из резолва. Упавший сервис
     * или activity -- худший ответ на это, чем невыполненное действие с записью в лог.
     */
    fun tryTransferIntentToProfile(context: Context, intent: Intent): Boolean = try {
        transferIntentToProfile(context, intent)
        true
    } catch (e: IllegalStateException) {
        false
    }

    fun tryTransferIntentToProfileUnsigned(context: Context, intent: Intent): Boolean = try {
        transferIntentToProfileUnsigned(context, intent)
        true
    } catch (e: IllegalStateException) {
        false
    }

    fun isWorkProfileAvailable(context: Context): Boolean {
        val storage = LocalStorageManager.getInstance()
        val intent = Intent(DummyActivity.TRY_START_SERVICE)
        return try {
            transferIntentToProfileUnsigned(context, intent)
            storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
            storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, true)
            true
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun isMIUI(): Boolean = MiuiDetector.isLikelyMiui(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        display = Build.DISPLAY
    )

    fun drawableToBitmap(drawable: Drawable, maxSizePx: Int = 0): Bitmap {
        if (drawable is BitmapDrawable) {
            val bitmap = drawable.bitmap
            return if (maxSizePx > 0) scaleBitmapToMax(bitmap, maxSizePx) else bitmap
        }

        var width = drawable.intrinsicWidth
        width = if (width > 0) width else 1
        var height = drawable.intrinsicHeight
        height = if (height > 0) height else 1

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return if (maxSizePx > 0) scaleBitmapToMax(bitmap, maxSizePx) else bitmap
    }

    /**
     * Pinned shortcut icon: opaque green PNG at shortcut scale (see generate_freeze_icons.ps1).
     * Flatten any stray alpha to green — Samsung shows transparency as white.
     */
    fun createBatchShortcutIcon(context: Context, @DrawableRes shortcutRes: Int): Icon {
        val app = context.applicationContext
        val decoded = BitmapFactory.decodeResource(app.resources, shortcutRes)
            ?: return Icon.createWithResource(app, shortcutRes)
        val opaque = flattenShortcutBitmap(decoded)
        if (opaque !== decoded) {
            decoded.recycle()
        }
        return Icon.createWithBitmap(scaleBitmapToMax(opaque, 512))
    }

    private const val SHORTCUT_ICON_GREEN = 0xFF223D2C.toInt()

    private fun flattenShortcutBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val flat = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flat)
        canvas.drawColor(SHORTCUT_ICON_GREEN)
        canvas.drawBitmap(source, 0f, 0f, null)
        return flat
    }

    private fun scaleBitmapToMax(source: Bitmap, maxSizePx: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxSizePx) {
            return source
        }
        val scale = maxSizePx.toFloat() / largest
        val targetW = (source.width * scale).toInt().coerceAtLeast(1)
        val targetH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    /** Delete generated files under the app cache directory (safe on cold start after reboot). */
    fun trimApplicationCache(context: Context) {
        val cacheRoot = context.cacheDir ?: return
        try {
            cacheRoot.listFiles()?.forEach { child ->
                child.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "trimApplicationCache failed", e)
        }
    }

    fun killMainServices(serviceMain: IGatekeeperService, serviceWork: IGatekeeperService) {
        try {
            serviceWork.stopGatekeeperService(true)
        } catch (e: Exception) {
        }

        try {
            serviceMain.stopGatekeeperService(false)
        } catch (e: Exception) {
        }
    }

    fun deleteMissingApps(pref: String, apps: List<ApplicationInfoWrapper>) {
        val list = ArrayList(LocalStorageManager.getInstance().getStringList(pref).toList())
        list.removeIf { item -> apps.none { x -> x.getPackageName() == item } }
        LocalStorageManager.getInstance().setStringList(pref, list.toTypedArray())
    }

    fun createLauncherShortcut(
        context: Context,
        launchIntent: Intent,
        icon: Icon,
        id: String,
        label: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java)

            if (shortcutManager.isRequestPinShortcutSupported) {
                // Пины ярлыка система/лаунчер вправе отклонить исключением (на A16 это
                // наблюдаемый у апстрима краш при создании ярлыка общей заморозки,
                // 4PDA #1706): отказ превращаем в тост, а не в падение приложения.
                try {
                    val info = ShortcutInfo.Builder(context, id)
                        .setIntent(launchIntent)
                        .setIcon(icon)
                        .setShortLabel(label)
                        .setLongLabel(label)
                        .build()
                    val addIntent = shortcutManager.createShortcutResultIntent(info)
                    shortcutManager.requestPinShortcut(
                        info,
                        PendingIntent.getBroadcast(
                            context,
                            0,
                            addIntent,
                            PendingIntent.FLAG_IMMUTABLE
                        ).intentSender
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "requestPinShortcut failed", e)
                    GatekeeperToast.show(
                        context,
                        context.getString(R.string.unsupported_launcher),
                        android.widget.Toast.LENGTH_LONG,
                    )
                }
            } else {
                GatekeeperToast.show(
                    context,
                    context.getString(R.string.unsupported_launcher),
                    android.widget.Toast.LENGTH_LONG,
                )
            }
        } else {
            val shortcutIntent = Intent("com.android.launcher.action.INSTALL_SHORTCUT")
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
            shortcutIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
            shortcutIntent.putExtra(
                Intent.EXTRA_SHORTCUT_ICON,
                drawableToBitmap(icon.loadDrawable(context)!!)
            )
            context.sendBroadcast(shortcutIntent)
            GatekeeperToast.show(context, R.string.shortcut_create_success)
        }
    }

    fun getMediaStoreId(context: Context, path: String): Int =
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DATA + " LIKE ? ",
            arrayOf(path),
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                -1
            } else {
                cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            }
        } ?: -1

    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight
                && halfWidth / inSampleSize >= reqWidth
            ) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }

    fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(filePath, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(filePath, options)!!
    }

    fun decodeSampledBitmap(fd: FileDescriptor, reqWidth: Int, reqHeight: Int): Bitmap {
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFileDescriptor(fd, null, options)

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)

        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFileDescriptor(fd, null, options)!!
    }

    fun getFileExtension(filePath: String): String? {
        val index = filePath.lastIndexOf(".")
        return if (index > 0) {
            filePath.substring(index + 1)
        } else {
            null
        }
    }

    fun checkUsageStatsPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_GET_USAGE_STATS)

    fun checkSystemAlertPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)

    @TargetApi(Build.VERSION_CODES.R)
    fun checkAllFileAccessPermission(): Boolean = Environment.isExternalStorageManager()

    fun checkSpecialAccessPermission(context: Context, name: String): Boolean {
        val appops = context.getSystemService(AppOpsManager::class.java)
        val mode = appops.checkOpNoThrow(name, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Throws(IOException::class)
    fun pipe(`is`: InputStream, os: OutputStream) {
        var n: Int
        val buffer = ByteArray(65536)
        while (`is`.read(buffer).also { n = it } > -1) {
            os.write(buffer, 0, n)
        }
    }

    class ActivityResultContractInputWrapper<I, O, T : ActivityResultContract<I, O>>(
        private val inner: T,
        private val input: I
    ) : ActivityResultContract<Void?, O>() {
        @NonNull
        override fun createIntent(@NonNull context: Context, input: Void?): Intent {
            return inner.createIntent(context, this.input)
        }

        override fun parseResult(resultCode: Int, @Nullable intent: Intent?): O {
            return inner.parseResult(resultCode, intent)
        }
    }
}
