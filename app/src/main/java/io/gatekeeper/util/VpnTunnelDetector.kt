package io.gatekeeper.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern

/**
 * Сложение сигналов [VpnTunnelDetector]. Отдельно от детектора: решение покрыто
 * JVM-тестами, а сами сигналы спрашивают систему.
 */
object VpnVerdict {
    private const val TAG = "VpnTunnelDetector"

    /** Ответы трех независимых сигналов; null -- сигнал спросить не удалось. */
    data class Signals(
        val tunnelInterface: Boolean?,
        val vpnTransport: Boolean?,
        val activeNetworkNotVpnMissing: Boolean?,
    )

    /**
     * VPN есть, если его видит хотя бы один сигнал. Сигнал без ответа голоса не дает,
     * поэтому при отказе всех трех ответ -- «VPN нет», и гейт пропускает запуск
     * (fail-open). Закрытый вариант ведет в тупик: гейт никогда не увидел бы VPN
     * погашенным -- ни после цикла вытеснения, ни после ручного отключения, -- и
     * приложения со снежинкой не запускались бы вовсе, пока с них не снимут снежинку.
     */
    fun verdict(signals: Signals): Boolean =
        signals.tunnelInterface == true ||
            signals.vpnTransport == true ||
            signals.activeNetworkNotVpnMissing == true

    /** Ответ сигнала или null, если он упал: сбой одного сигнала не решает за остальные. */
    @Suppress("TooGenericExceptionCaught") // SocketException сканирования, отказы ConnectivityManager
    fun probe(name: String, signal: () -> Boolean): Boolean? = try {
        signal()
    } catch (e: Exception) {
        Log.w(TAG, "VPN signal $name failed", e)
        null
    }
}

/**
 * Lean local VPN/tunnel detection (no network I/O). Same signals MacroDroid-style triggers use.
 */
object VpnTunnelDetector {
    private const val TAG = "VpnTunnelDetector"

    private val TUNNEL_NAME = Pattern.compile(
        "^(tun\\d+|wg\\d+|ppp\\d+|tap\\d+|vpn\\d+|utun\\d+|rmnet_data\\d+)$",
        Pattern.CASE_INSENSITIVE
    )

    fun isVpnActive(context: Context): Boolean = VpnVerdict.verdict(probeAll(context))

    fun logDiagnostics(context: Context) {
        val signals = probeAll(context)
        Log.i(
            TAG,
            "vpn diag tun=${signals.tunnelInterface} transport=${signals.vpnTransport} " +
                "activeNet=${signals.activeNetworkNotVpnMissing} => ${VpnVerdict.verdict(signals)}"
        )
    }

    /**
     * Каждый сигнал -- в своем try: раньше исключение ConnectivityManager обрывало всю
     * проверку в false, и следующий сигнал уже не спрашивался.
     */
    private fun probeAll(context: Context): VpnVerdict.Signals = VpnVerdict.Signals(
        tunnelInterface = VpnVerdict.probe("interfaces") { hasTunnelInterface() },
        vpnTransport = VpnVerdict.probe("transport") {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm != null && (anyNetworkHasVpnTransport(cm) || activeNetworkIsVpn(cm))
        },
        activeNetworkNotVpnMissing = VpnVerdict.probe("not_vpn") {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm != null && activeNetworkMissingNotVpnCapability(cm)
        },
    )

    private fun hasTunnelInterface(): Boolean {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        val tunnel = Collections.list(interfaces).firstOrNull { ni ->
            val name = ni.name
            name != null && isTunnelInterfaceName(name) && ni.isUp && !ni.isLoopback
        }
        if (tunnel != null) {
            Log.d(TAG, "tunnel iface: ${tunnel.name}")
        }
        return tunnel != null
    }

    /** Имена туннельных интерфейсов; вынесено ради прямого юнит-теста. */
    internal fun isTunnelInterfaceName(name: String): Boolean =
        TUNNEL_NAME.matcher(name.lowercase(Locale.US)).matches()

    // allNetworks устарел в пользу NetworkCallback, но синхронного снимка всех сетей
    // другого публичного API нет: здесь разовая проверка, а не подписка (подписка --
    // в AntiSpyVpnWatchService).
    @Suppress("DEPRECATION")
    private fun anyNetworkHasVpnTransport(cm: ConnectivityManager): Boolean {
        return cm.allNetworks
            .mapNotNull { network -> cm.getNetworkCapabilities(network)?.let { network to it } }
            .any { (network, caps) ->
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> {
                        Log.d(TAG, "TRANSPORT_VPN network=$network")
                        true
                    }
                    hasVpnTransportInfo(caps) -> {
                        Log.d(TAG, "VpnTransportInfo network=$network")
                        true
                    }
                    else -> false
                }
            }
    }

    /** [NetworkCapabilities.getTransportInfo] отдает VpnTransportInfo начиная с API 29. */
    private fun hasVpnTransportInfo(caps: NetworkCapabilities): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val transportInfo = caps.transportInfo
        return transportInfo != null && "android.net.VpnTransportInfo" == transportInfo.javaClass.name
    }

    /** Сеть по умолчанию -- VPN. Замена устаревшим activeNetworkInfo/TYPE_VPN (API 23+). */
    private fun activeNetworkIsVpn(cm: ConnectivityManager): Boolean {
        val network = cm.activeNetwork ?: return false
        val active = cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (active) {
            Log.d(TAG, "default network is VPN")
        }
        return active
    }

    /**
     * Идет ли трафик **этого** пользователя через туннель.
     *
     * В отличие от [isVpnActive] не смотрит на список интерфейсов: `tun0` принадлежит
     * устройству и виден из любого профиля, поэтому по нему нельзя отличить «я в туннеле»
     * от «туннель есть у соседнего пользователя». Возможности активной сети выдаются
     * каждому пользователю отдельно и отвечают ровно на нужный вопрос.
     */
    fun isDefaultNetworkTunneled(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } catch (e: Exception) {
            Log.w(TAG, "default network probe failed", e)
            false
        }
    }

    private fun activeNetworkMissingNotVpnCapability(cm: ConnectivityManager): Boolean {
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        val missing = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (missing) {
            Log.d(TAG, "active network lacks NOT_VPN")
        }
        return missing
    }
}
