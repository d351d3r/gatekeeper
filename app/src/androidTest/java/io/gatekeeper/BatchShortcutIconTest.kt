package io.gatekeeper

import android.content.pm.ShortcutInfo
import android.graphics.drawable.BitmapDrawable
import androidx.test.platform.app.InstrumentationRegistry
import io.gatekeeper.util.Utility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт иконки ярлыка общей заморозки (4PDA #1706: краш создания ярлыка
 * на Android 16 у апстрима; гипотеза — adaptive-icon через createWithResource).
 *
 * Gatekeeper отдаёт растровую иконку: этот тест шпыняет контракт, чтобы
 * регрессия до ресурсной иконки не прошла незамеченной. Прогон — на AVD
 * Android 16 (`gatekeeper_a16`), см. plan.md A2.
 */
class BatchShortcutIconTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun freezeAllShortcutIconIsOpaqueBitmapWithinShortcutBounds() {
        val icon = Utility.createBatchShortcutIcon(context, R.drawable.ic_shortcut_freeze)

        assertEquals("icon must stay bitmap-backed (raster contract)", android.graphics.drawable.Icon.TYPE_BITMAP, icon.type)

        val drawable = checkNotNull(icon.loadDrawable(context))
        val largest = maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight)
        assertTrue("icon $largest px exceeds 512 px shortcut bound", largest in 1..512)
    }

    @Test
    fun shortcutInfoBuilderAcceptsBatchIcon() {
        // Тот же набор вызовов, что в Utility.createLauncherShortcut: если система
        // на текущей платформе отвергает такую иконку, всплывёт здесь, а не крашом UI.
        val icon = Utility.createBatchShortcutIcon(context, R.drawable.ic_shortcut_unfreeze)
        val info = ShortcutInfo.Builder(context, "gatekeeper-test-unfreeze-all")
            .setIntent(android.content.Intent("io.gatekeeper.action.PUBLIC_UNFREEZE_ALL"))
            .setIcon(icon)
            .setShortLabel("test")
            .setLongLabel("test")
            .build()
        assertNotNull(info)
    }
}
