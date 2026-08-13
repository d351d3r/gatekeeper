package io.gatekeeper.ui

import android.app.Activity
import android.os.Bundle
import io.gatekeeper.R
import io.gatekeeper.util.FileShuttleConnection
import io.gatekeeper.util.ZindanToast

/**
 * Точка входа для действия, которое клиент SAF показывает пользователю, когда связи с
 * файловым шаттлом нет. Activity видима, поэтому кросс-профильный запуск из нее -- обычный
 * foreground-старт, а не блокируемый системой фоновый.
 *
 * Гейт не нужен: activity не экспортирована, а единственный способ ее запустить снаружи --
 * наш собственный неизменяемый `PendingIntent`, который система отправляет от нашего имени.
 * Подписью по таймстампу такой запуск гейтить нельзя: `PendingIntent` живет в system_server
 * дольше окна подписи.
 */
class FileShuttleAuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (FileShuttleConnection.peek() != null) {
            done(true)
            return
        }
        if (!FileShuttleConnection.requestBind(this)) {
            ZindanToast.show(this, R.string.file_shuttle_unavailable)
            done(false)
            return
        }

        Thread {
            val bound = FileShuttleConnection.awaitBinder(FileShuttleConnection.BIND_TIMEOUT_MS) != null
            runOnUiThread {
                if (!bound) {
                    ZindanToast.show(this, R.string.file_shuttle_unavailable)
                }
                done(bound)
            }
        }.start()
    }

    private fun done(bound: Boolean) {
        // Единственный сигнал, по которому клиент SAF перечитывает каталог, если он
        // отправлял действие через startIntentSenderForResult.
        setResult(if (bound) RESULT_OK else RESULT_CANCELED)
        finish()
    }
}
