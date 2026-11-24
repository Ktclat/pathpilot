package dev.pathpilot.app.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

object LocalAddresses {
    /** Call on IO. Does not bind sockets or alter the system/VPN network selection. */
    fun discover(context: Context): LocalAddressSnapshot {
        val networks = mutableListOf<LocalAddress>()
        val interfaces = mutableListOf<LocalAddress>()
        var incomplete = false
        fun failed(operation: String, error: Exception) {
            incomplete = true
            Log.w("PathPilotAddresses", "$operation: ${error.javaClass.simpleName}")
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            try {
                @Suppress("DEPRECATION")
                val availableNetworks = connectivity.allNetworks
                availableNetworks.forEach { network ->
                    val capabilities = connectivity.getNetworkCapabilities(network) ?: return@forEach
                    val links = connectivity.getLinkProperties(network) ?: return@forEach
                    val kind = LocalAddressRules.kind(
                        vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                    )
                    collectLinks(links, kind, networks)
                }
            } catch (error: SecurityException) {
                failed("read network transports", error)
            }
        } else {
            incomplete = true
            Log.w("PathPilotAddresses", "ConnectivityManager unavailable")
        }
        // Hotspot, USB and other local interfaces are not always exposed as Android Networks.
        // Their names are shown as-is; a name alone does not establish their transport type.
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { networkInterface ->
                try {
                    if (networkInterface.isUp && !networkInterface.isLoopback) {
                        networkInterface.inetAddresses.toList().filterIsInstance<Inet4Address>().forEach { address ->
                            address.hostAddress?.let { interfaces += LocalAddress(it, networkInterface.name, LocalAddressKind.OTHER) }
                        }
                    }
                } catch (error: SocketException) {
                    failed("read local interface", error)
                }
            }
        } catch (error: SocketException) {
            failed("enumerate local interfaces", error)
        } catch (error: SecurityException) {
            failed("enumerate local interfaces", error)
        }
        val addresses = LocalAddressRules.merge(networks, interfaces)
        return LocalAddressSnapshot(addresses, if (!incomplete) null
            else if (addresses.isEmpty()) "无法读取本机 IPv4 地址" else "部分网络信息无法读取")
    }

    private fun collectLinks(links: LinkProperties, kind: LocalAddressKind, result: MutableList<LocalAddress>) {
        links.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().forEach { address ->
            address.hostAddress?.let { result += LocalAddress(it, links.interfaceName.orEmpty(), kind) }
        }
    }
}
