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
 * Lean local VPN/tunnel detection (no network I/O). Same signals MacroDroid-style triggers use.
 */
object VpnTunnelDetector {
    private const val TAG = "VpnTunnelDetector"

    private val TUNNEL_NAME = Pattern.compile(
        "^(tun\\d+|wg\\d+|ppp\\d+|tap\\d+|vpn\\d+|utun\\d+|rmnet_data\\d+)$",
        Pattern.CASE_INSENSITIVE
    )

    fun isVpnActive(context: Context): Boolean {
        return try {
            hasTunnelInterface()
                    || hasVpnNetworkTransport(context)
                    || activeNetworkMissingNotVpnCapability(context)
        } catch (e: Exception) {
            Log.w(TAG, "VPN detection failed", e)
            false
        }
    }

    fun logDiagnostics(context: Context) {
        val tun = hasTunnelInterface()
        val transport = hasVpnNetworkTransport(context)
        val active = activeNetworkMissingNotVpnCapability(context)
        Log.i(
            TAG,
            "vpn diag tun=$tun transport=$transport activeNet=$active => ${tun || transport || active}"
        )
    }

    private fun hasTunnelInterface(): Boolean {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
            for (ni in Collections.list(interfaces)) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name ?: continue
                if (isTunnelInterfaceName(name)) {
                    Log.d(TAG, "tunnel iface: $name")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Interface scan failed", e)
        }
        return false
    }

    /** Имена туннельных интерфейсов; вынесено ради прямого юнит-теста. */
    internal fun isTunnelInterfaceName(name: String): Boolean =
        TUNNEL_NAME.matcher(name.lowercase(Locale.US)).matches()

    private fun hasVpnNetworkTransport(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return anyNetworkHasVpnTransport(cm) || activeNetworkIsVpn(cm)
    }

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

    private fun activeNetworkMissingNotVpnCapability(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val missing = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        if (missing) {
            Log.d(TAG, "active network lacks NOT_VPN")
        }
        return missing
    }
}
