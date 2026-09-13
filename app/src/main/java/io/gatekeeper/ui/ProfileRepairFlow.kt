package io.gatekeeper.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import io.gatekeeper.R
import io.gatekeeper.util.AuthenticationUtility
import io.gatekeeper.util.PairingCode

/**
 * Перепривязка личной копии к живому профилю (REQUEST_REPAIR), рабочая сторона.
 *
 * Удаление личной копии стирает ее данные вместе с общим HMAC-ключом. При
 * переустановке личная сторона заводит новый ключ, рабочая продолжает проверять
 * подписи старым и отклоняет все -- а принять новый молча не может: тогда любое
 * приложение переписало бы ключ форварднутым интентом (IntentForwarderActivity
 * работает в системном процессе, exported и permissions на нем не действуют).
 * Без этого потока выход из расхождения один -- удалить профиль со всем
 * содержимым.
 *
 * Поэтому ключ меняет человек: диалог показывает код, выведенный из предложенного
 * ключа, и тот же код показывает личная копия перед отправкой запроса. Подделать
 * интент чужое приложение может, показать совпадающий код на экране личного
 * Gatekeeper -- нет.
 */
class ProfileRepairFlow(private val activity: DummyActivity) {

    fun handleRequestRepair() {
        val offered = activity.intent.getStringExtra(EXTRA_AUTH_KEY)
        if (offered.isNullOrEmpty()) {
            activity.finish()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.repair_confirm_title)
            .setMessage(activity.getString(R.string.repair_confirm_message, PairingCode.of(offered)))
            .setCancelable(false)
            .setPositiveButton(R.string.repair_confirm_allow) { _, _ ->
                // Результат ставим по факту принятия: негодный ключ личная
                // сторона должна увидеть как отказ, а не как успех.
                if (AuthenticationUtility.adoptKey(offered)) {
                    activity.setResult(Activity.RESULT_OK)
                }
                activity.finish()
            }
            .setNegativeButton(R.string.repair_confirm_deny) { _, _ -> activity.finish() }
            .show()
    }

    companion object {
        const val EXTRA_AUTH_KEY = "auth_key"
    }
}
