package io.gatekeeper.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import io.gatekeeper.R
import io.gatekeeper.util.ApplicationInfoWrapper
import io.gatekeeper.util.AutoFreezePolicy
import io.gatekeeper.util.LocalStorageManager

internal const val MENU_ITEM_CLONE = 10001
internal const val MENU_ITEM_UNINSTALL = 10002
internal const val MENU_ITEM_FREEZE = 10003
internal const val MENU_ITEM_UNFREEZE = 10004
internal const val MENU_ITEM_LAUNCH = 10005
internal const val MENU_ITEM_CREATE_UNFREEZE_SHORTCUT = 10006
internal const val MENU_ITEM_AUTO_FREEZE = 10007
internal const val MENU_ITEM_ALLOW_CROSS_PROFILE_WIDGET = 10008
internal const val MENU_ITEM_ALLOW_CROSS_PROFILE_INTERACTION = 10009
internal const val MENU_ITEM_PERMISSIONS = 10010
internal const val MENU_ITEM_TRAFFIC = 10011

/**
 * Пункт листа действий. Последствие названо у каждого: раньше строка была одним
 * словом («Заморозить»), и что именно случится, знал только автор.
 */
internal class AppMenuEntry(
    val itemId: Int,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int,
    @StringRes val descRes: Int,
    val checkable: Boolean = false,
    val checked: Boolean = false,
)

/** Состав листа: что именно предложить для этого приложения. */
internal object AppMenuEntries {

    fun build(
        app: ApplicationInfoWrapper,
        isRemote: Boolean,
        widgetProviders: Set<String>,
        crossProfilePackages: Set<String>,
    ): List<AppMenuEntry> {
        val entries = ArrayList<AppMenuEntry>()
        if (isRemote) {
            entries.addAll(workAppActions(app))
            entries.addAll(workAppToggles(app, widgetProviders, crossProfilePackages))
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_CREATE_UNFREEZE_SHORTCUT,
                    R.string.create_unfreeze_shortcut,
                    R.drawable.ic_shortcut,
                    R.string.app_sheet_shortcut_desc,
                )
            )
        } else {
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_CLONE,
                    R.string.clone_to_work_profile,
                    R.drawable.ic_copy,
                    R.string.app_sheet_clone_work_desc,
                )
            )
        }
        if (!app.isSystem()) {
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_UNINSTALL,
                    R.string.uninstall_app,
                    R.drawable.ic_delete,
                    R.string.app_sheet_uninstall_desc,
                )
            )
        }
        return entries
    }

    /**
     * Разовые действия над приложением профиля. Заморозка предлагается только тем,
     * кто в списке автозаморозки: остальные морозятся целиком, а не по одному.
     */
    private fun workAppActions(app: ApplicationInfoWrapper): List<AppMenuEntry> {
        val entries = ArrayList<AppMenuEntry>()
        if (!app.isSystem()) {
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_CLONE,
                    R.string.clone_to_main_profile,
                    R.drawable.ic_copy,
                    R.string.app_sheet_clone_main_desc,
                )
            )
        }
        if (app.isHidden()) {
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_UNFREEZE,
                    R.string.unfreeze_app,
                    R.drawable.ic_unfreeze,
                    R.string.app_sheet_unfreeze_desc,
                )
            )
        } else if (AutoFreezePolicy.isInAutoFreezeList(app.getPackageName())) {
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_FREEZE,
                    R.string.freeze_app,
                    R.drawable.ic_freeze,
                    R.string.app_sheet_freeze_desc,
                )
            )
        }
        entries.add(
            AppMenuEntry(
                MENU_ITEM_PERMISSIONS,
                R.string.app_sheet_permissions,
                R.drawable.ic_verified_user,
                R.string.app_sheet_permissions_desc,
            )
        )
        entries.add(
            AppMenuEntry(
                MENU_ITEM_TRAFFIC,
                R.string.app_sheet_traffic,
                R.drawable.ic_link,
                R.string.app_sheet_traffic_desc,
            )
        )
        entries.add(
            AppMenuEntry(
                MENU_ITEM_LAUNCH,
                R.string.launch,
                R.drawable.ic_launch,
                R.string.app_sheet_launch_desc,
            )
        )
        return entries
    }

    /** Переключатели: две дырки в изоляции и участие в списке автозаморозки. */
    private fun workAppToggles(
        app: ApplicationInfoWrapper,
        widgetProviders: Set<String>,
        crossProfilePackages: Set<String>,
    ): List<AppMenuEntry> {
        val pkg = app.getPackageName()
        val entries = ArrayList<AppMenuEntry>()
        entries.add(
            AppMenuEntry(
                MENU_ITEM_ALLOW_CROSS_PROFILE_WIDGET,
                R.string.allow_cross_profile_widgets,
                R.drawable.ic_widgets,
                R.string.app_sheet_widget_desc,
                checkable = true,
                checked = pkg in widgetProviders,
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            entries.add(
                AppMenuEntry(
                    MENU_ITEM_ALLOW_CROSS_PROFILE_INTERACTION,
                    R.string.allow_cross_profile_interaction,
                    R.drawable.ic_file_shuttle,
                    R.string.app_sheet_interaction_desc,
                    checkable = true,
                    checked = pkg in crossProfilePackages,
                )
            )
        }
        entries.add(
            AppMenuEntry(
                MENU_ITEM_AUTO_FREEZE,
                R.string.auto_freeze,
                R.drawable.ic_freeze,
                R.string.app_sheet_auto_freeze_desc,
                checkable = true,
                checked = LocalStorageManager.getInstance().stringListContains(
                    LocalStorageManager.PREF_AUTO_FREEZE_LIST_WORK_PROFILE,
                    pkg
                ),
            )
        )
        return entries
    }
}

/**
 * Лист действий над приложением (G4). Раньше это был AlertDialog, собранный из
 * TextView руками, где состояние переключателя рисовалось символами. Здесь шапка
 * отвечает «над чем именно», у каждой строки иконка и последствие, а у
 * переключателя настоящий Switch.
 */
internal class AppActionSheet(
    private val context: Context,
    private val inflater: LayoutInflater,
) {

    fun show(
        app: ApplicationInfoWrapper,
        entries: List<AppMenuEntry>,
        icon: Bitmap?,
        onSelected: (AppMenuEntry) -> Unit,
        onDismiss: () -> Unit,
    ) {
        val sheet = BottomSheetDialog(context)
        val view = inflater.inflate(R.layout.dialog_app_actions, null)
        sheet.setContentView(view)

        view.findViewById<TextView>(R.id.sheet_app_name).text = app.getLabel()
        view.findViewById<TextView>(R.id.sheet_app_state).setText(
            if (app.isHidden()) R.string.row_state_frozen else R.string.row_state_running
        )
        val iconView = view.findViewById<ImageView>(R.id.sheet_app_icon)
        if (icon != null) {
            iconView.setImageBitmap(icon)
        } else {
            iconView.setImageDrawable(context.packageManager.defaultActivityIcon)
        }

        val container = view.findViewById<LinearLayout>(R.id.sheet_actions)
        for (entry in entries) {
            container.addView(buildRow(container, entry) {
                sheet.dismiss()
                onSelected(entry)
            })
        }

        sheet.setOnDismissListener { onDismiss() }
        sheet.show()
    }

    private fun buildRow(
        container: LinearLayout,
        entry: AppMenuEntry,
        onClick: () -> Unit,
    ): View {
        val layoutRes = if (entry.checkable) R.layout.app_sheet_toggle else R.layout.app_sheet_action
        val row = inflater.inflate(layoutRes, container, false)
        row.findViewById<ImageView>(R.id.sheet_row_icon).setImageResource(entry.iconRes)
        row.findViewById<TextView>(R.id.sheet_row_title).setText(entry.labelRes)
        row.findViewById<TextView>(R.id.sheet_row_desc).setText(entry.descRes)
        if (entry.checkable) {
            row.findViewById<MaterialSwitch>(R.id.sheet_row_switch).isChecked = entry.checked
        }
        row.setOnClickListener { onClick() }
        return row
    }
}
