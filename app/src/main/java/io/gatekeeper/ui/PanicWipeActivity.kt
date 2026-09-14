package io.gatekeeper.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import io.gatekeeper.R
import io.gatekeeper.util.GatekeeperToast
import io.gatekeeper.util.LocalStorageManager
import io.gatekeeper.util.Utility

/**
 * Подтверждение паник-удаления рабочего профиля. Открывается плиткой быстрых
 * настроек (PanicWipeTileService). Удаление необратимо и уносит все приложения и
 * данные профиля, поэтому подтверждение обязательно. После «Стереть» шлем
 * подписанное WIPE_PROFILE рабочей стороне, где profile owner зовет wipeData.
 */
class PanicWipeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle(R.string.panic_wipe_title)
            .setMessage(R.string.panic_wipe_message)
            .setPositiveButton(R.string.panic_wipe_confirm) { _, _ -> wipe() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun wipe() {
        val intent = Intent(DummyActivity.WIPE_PROFILE)
        if (Utility.tryTransferIntentToProfile(this, intent)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            // Профиль уносится -- следующий запуск должен вести в мастер создания, а не
            // упираться в «нет связи с профилем». Сбрасываем флаги настройки; если вайп
            // почему-то не прошёл, isWorkProfileAvailable восстановит их на живом профиле.
            val storage = LocalStorageManager.getInstance()
            storage.setBoolean(LocalStorageManager.PREF_HAS_SETUP, false)
            storage.setBoolean(LocalStorageManager.PREF_IS_SETTING_UP, false)
        } else {
            GatekeeperToast.show(this, R.string.panic_wipe_unavailable)
        }
        finish()
    }
}
