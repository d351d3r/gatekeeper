package io.gatekeeper.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File
import java.net.NetworkInterface

/**
 * Что приложение внутри рабочего профиля может узнать о сети и о соседнем
 * профиле. Проверяются только обычные API, без прав владельца профиля: по
 * сетевой части у Gatekeeper здесь ровно те же возможности, что у любого
 * клона с разрешением INTERNET.
 *
 * Вопрос, ради которого пробник написан: видит ли клон адрес VPN-сервера и
 * приложения другого профиля.
 *
 * Запуск: adb shell am broadcast --user <профиль> -n io.gatekeeper/io.gatekeeper.debug.LeakProbeReceiver
 */
class LeakProbeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "$MARK ---- begin ----")
        logInterfaces()
        logProcNet()
        logActiveNetwork(context)
        logPackages(context)
        Log.i(TAG, "$MARK ---- end ----")
    }

    /** Интерфейсы читаются из общего для устройства /proc/net/dev. */
    private fun logInterfaces() {
        val names = try {
            val list = NetworkInterface.getNetworkInterfaces()
            if (list == null) {
                Log.i(TAG, "$MARK интерфейсы: платформа не отдала список")
                return
            }
            list.toList().map { nif ->
                val addrs = nif.inetAddresses.toList().joinToString(",") { it.hostAddress ?: "?" }
                "${nif.name}[$addrs]"
            }
        } catch (e: Exception) {
            listOf("ошибка: ${e.message}")
        }
        Log.i(TAG, "$MARK интерфейсы: ${names.joinToString(" ")}")
    }

    /** Таблица маршрутов и сокетов: там был бы адрес VPN-сервера. */
    private fun logProcNet() {
        for (path in PROC_PATHS) {
            val file = File(path)
            val verdict = try {
                if (!file.exists()) {
                    "нет файла"
                } else {
                    val lines = file.readLines()
                    "читается, строк ${lines.size}"
                }
            } catch (e: Exception) {
                "закрыто: ${e.javaClass.simpleName}"
            }
            Log.i(TAG, "$MARK $path: $verdict")
        }
    }

    private fun logActiveNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val net = cm.activeNetwork
        if (net == null) {
            Log.i(TAG, "$MARK активная сеть: нет")
            return
        }
        val caps = cm.getNetworkCapabilities(net)
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val link = cm.getLinkProperties(net)
        Log.i(TAG, "$MARK активная сеть: транспорт VPN=$vpn интерфейс=${link?.interfaceName}")
        Log.i(TAG, "$MARK   DNS: ${link?.dnsServers?.joinToString(",") { it.hostAddress ?: "?" }}")
        Log.i(TAG, "$MARK   маршруты: ${link?.routes?.joinToString(" ") { it.toString() }}")
        val all = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        Log.i(TAG, "$MARK   всего сетей видно: ${all.size}")
    }

    /** Пакеты соседнего профиля: отдельный пользователь, отдельный список. */
    private fun logPackages(context: Context) {
        val mine = context.packageManager.getInstalledApplications(0).size
        Log.i(TAG, "$MARK пакетов видно из профиля: $mine")
    }

    private companion object {
        const val TAG = "LeakProbe"
        const val MARK = "##"
        val PROC_PATHS = listOf(
            "/proc/net/route",
            "/proc/net/tcp",
            "/proc/net/tcp6",
            "/proc/net/udp",
            "/proc/net/arp",
        )
    }
}
