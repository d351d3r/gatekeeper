package io.gatekeeper.util

import android.os.Binder
import android.os.IBinder
import android.os.Process

/**
 * Кто вправе звать наши AIDL-интерфейсы: только Gatekeeper, в любом профиле.
 *
 * Биндеры сервисов уходят через границу профиля в extras интентов, а такие интенты
 * в целевом профиле резолвит системный форвардер, и перехватить их может любое
 * приложение, объявившее те же действия (docs/audit_android17.md, S1). Поэтому
 * владение биндером само по себе ничего не доказывает: проверяется вызывающий uid.
 *
 * Один и тот же пакет во всех пользователях устройства получает один appId, а uid
 * складывается как `userId * PER_USER_RANGE + appId`. Публичного UserHandle.getAppId
 * в SDK нет, поэтому appId считается по тому же правилу, что и в платформе.
 */
object BinderCallers {
    /** UserHandle.PER_USER_RANGE: неизменен со времен многопользовательского Android 4.2. */
    private const val PER_USER_RANGE = 100_000

    fun appIdOf(uid: Int): Int = uid % PER_USER_RANGE

    /** Тот же пакет, возможно в другом профиле. */
    fun isSameApp(callingUid: Int, ownUid: Int): Boolean = appIdOf(callingUid) == appIdOf(ownUid)

    /**
     * Методы AIDL занимают коды [IBinder.FIRST_CALL_TRANSACTION]..[IBinder.LAST_CALL_TRANSACTION].
     * Служебные транзакции (ping, dump, запрос дескриптора интерфейса) не трогаем.
     */
    fun isAidlCall(code: Int): Boolean =
        code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION

    /**
     * Звать из onTransact заглушки: исключение уходит вызывающему как SecurityException,
     * метод не исполняется.
     */
    fun enforceGatekeeperCaller(code: Int) {
        if (!isAidlCall(code)) return
        val callingUid = Binder.getCallingUid()
        if (!isSameApp(callingUid, Process.myUid())) {
            throw SecurityException("uid $callingUid is not Gatekeeper")
        }
    }
}
