package dev.pathpilot.app.runtime

import java.net.Inet4Address
import java.net.InetAddress

enum class LocalAddressKind { WIFI, ETHERNET, OTHER, VPN, CELLULAR }

/** A local interface address, not a claim that another device can reach it. */
data class LocalAddress(val ip: String, val interfaceName: String, val kind: LocalAddressKind)

data class LocalAddressSnapshot(val addresses: List<LocalAddress>, val warning: String? = null)

/** Platform-independent merge rules also used by the address discovery regression tests. */
internal object LocalAddressRules {
    fun kind(vpn: Boolean, wifi: Boolean, ethernet: Boolean, cellular: Boolean): LocalAddressKind = when {
        vpn -> LocalAddressKind.VPN
        wifi -> LocalAddressKind.WIFI
        ethernet -> LocalAddressKind.ETHERNET
        cellular -> LocalAddressKind.CELLULAR
        else -> LocalAddressKind.OTHER
    }

    fun merge(networks: List<LocalAddress>, interfaces: List<LocalAddress>): List<LocalAddress> {
        val validInterfaces = interfaces.mapNotNull(::valid)
        val validNetworks = networks.mapNotNull(::valid).map { address ->
            if (address.interfaceName.isNotEmpty()) address
            else address.copy(interfaceName = validInterfaces.filter { it.ip == address.ip }
                .map { it.interfaceName }.distinct().singleOrNull().orEmpty())
        }
        // LinkProperties can omit secondary IPs. A known transport still applies to every
        // address on that interface, especially VPN/cellular aliases from the fallback scan.
        val interfaceKinds = validNetworks.filter { it.interfaceName.isNotEmpty() }
            .groupBy { it.interfaceName }.mapValues { (_, addresses) -> preferredKind(addresses.map { it.kind }) }
        // The platform's transport classification wins over the NetworkInterface fallback.
        // Keep equal IPs on distinct interfaces: they describe different possible network paths.
        return (validNetworks + validInterfaces)
            .map { address -> interfaceKinds[address.interfaceName]?.let { address.copy(kind = it) } ?: address }
            .groupBy { it.ip to it.interfaceName }
            .map { (_, candidates) -> candidates.first().copy(kind = preferredKind(candidates.map { it.kind })) }
            .sortedWith(compareBy<LocalAddress> { it.kind.ordinal }.thenBy { it.interfaceName }
                .thenBy { numericIp(it.ip) })
    }

    private fun preferredKind(kinds: List<LocalAddressKind>): LocalAddressKind =
        if (LocalAddressKind.VPN in kinds) LocalAddressKind.VPN
        else kinds.filter { it != LocalAddressKind.OTHER }.minByOrNull { it.ordinal } ?: LocalAddressKind.OTHER

    private fun valid(address: LocalAddress): LocalAddress? {
        val octets = address.ip.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        val ip = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray()) as Inet4Address
        if (ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isAnyLocalAddress || ip.isMulticastAddress) return null
        return address.copy(ip = octets.joinToString("."))
    }

    private fun numericIp(ip: String): Long = ip.split('.').fold(0L) { result, part -> (result shl 8) + part.toInt() }
}
