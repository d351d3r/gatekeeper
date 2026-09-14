package io.gatekeeper.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.materialswitch.MaterialSwitch
import io.gatekeeper.R
import io.gatekeeper.services.IGatekeeperService
import io.gatekeeper.util.PermGroup
import io.gatekeeper.util.PermissionGroups

/**
 * Пер-аппный отзыв опасных разрешений у приложения рабочего профиля. Показываем
 * только группы, которые приложение объявило. Тумблер выкл = разрешение запрещено
 * политикой (setPermissionGrantState DENIED): приложение не может им пользоваться и
 * не может его запросить. Открывается из листа действий над work-приложением.
 */
class AppPermissionsActivity : AppCompatActivity() {

    private var work: IGatekeeperService? = null
    private var pkg: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_permissions)

        val binder = intent.getBundleExtra(EXTRA_BUNDLE)?.getBinder(EXTRA_SERVICE)
        work = IGatekeeperService.Stub.asInterface(binder)
        pkg = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()

        setSupportActionBar(findViewById(R.id.perm_toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = label.ifEmpty { getString(R.string.app_permissions_title) }
        }

        val scroll = findViewById<View>(R.id.perm_scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            windowInsets
        }

        if (work == null || pkg.isEmpty()) {
            finish()
            return
        }
        load()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun load() {
        val container = findViewById<LinearLayout>(R.id.perm_container)
        Thread {
            val declared = declaredPermissions()
            val groups = PermissionGroups.groupsFor(declared)
            val blocked = groups.associateWith { group -> isGroupBlocked(group) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (groups.isEmpty()) {
                    findViewById<TextView>(R.id.perm_empty).visibility = View.VISIBLE
                    return@runOnUiThread
                }
                groups.forEach { group -> container.addView(buildRow(group, blocked[group] == true)) }
            }
        }.start()
    }

    private fun declaredPermissions(): Set<String> = try {
        work?.getDeniablePermissions(pkg)?.toSet() ?: emptySet()
    } catch (_: RemoteException) {
        emptySet()
    }

    private fun isGroupBlocked(group: PermGroup): Boolean =
        group.permissions.any { stateOf(it) == STATE_DENIED }

    private fun stateOf(permission: String): Int = try {
        work?.getPermissionState(pkg, permission) ?: STATE_DEFAULT
    } catch (_: RemoteException) {
        STATE_DEFAULT
    }

    private fun buildRow(group: PermGroup, blocked: Boolean): View {
        val container = findViewById<LinearLayout>(R.id.perm_container)
        val row = layoutInflater.inflate(R.layout.perm_group_row, container, false)
        row.findViewById<TextView>(R.id.perm_row_title).setText(group.labelRes)
        val toggle = row.findViewById<MaterialSwitch>(R.id.perm_row_switch)
        // Вкл = разрешено (default), выкл = запрещено политикой.
        toggle.isChecked = !blocked
        toggle.setOnCheckedChangeListener { _, allowed -> applyGroup(group, allowed) }
        return row
    }

    private fun applyGroup(group: PermGroup, allowed: Boolean) {
        val state = if (allowed) STATE_DEFAULT else STATE_DENIED
        Thread {
            group.permissions.forEach { permission ->
                try {
                    work?.setPermissionState(pkg, permission, state)
                } catch (_: RemoteException) {
                    // Профиль недоступен -- строка вернется в прежнее состояние при перезаходе.
                }
            }
        }.start()
    }

    companion object {
        const val EXTRA_BUNDLE = "extras"
        const val EXTRA_SERVICE = "profile_service"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"

        private const val STATE_DEFAULT = 0
        private const val STATE_DENIED = 2

        fun start(context: Context, work: IGatekeeperService, pkg: String, label: String) {
            context.startActivity(
                Intent(context, AppPermissionsActivity::class.java).apply {
                    putExtra(EXTRA_PACKAGE, pkg)
                    putExtra(EXTRA_LABEL, label)
                    putExtra(EXTRA_BUNDLE, Bundle().apply { putBinder(EXTRA_SERVICE, work.asBinder()) })
                }
            )
        }
    }
}
