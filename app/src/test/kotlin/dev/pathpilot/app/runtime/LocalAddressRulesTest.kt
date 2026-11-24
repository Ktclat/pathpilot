package dev.pathpilot.app.runtime

import org.junit.Assert.*
import org.junit.Test

class LocalAddressRulesTest {
    @Test fun vpnTransportWinsEvenWhenItAlsoReportsItsUnderlyingWifi() {
        assertEquals(LocalAddressKind.VPN, LocalAddressRules.kind(vpn = true, wifi = true, ethernet = false, cellular = true))
        assertEquals(LocalAddressKind.WIFI, LocalAddressRules.kind(vpn = false, wifi = true, ethernet = false, cellular = false))
        assertEquals(LocalAddressKind.ETHERNET, LocalAddressRules.kind(vpn = false, wifi = false, ethernet = true, cellular = false))
        assertEquals(LocalAddressKind.CELLULAR, LocalAddressRules.kind(vpn = false, wifi = false, ethernet = false, cellular = true))
    }

    @Test fun mergesInterfaceFallbackWithoutLosingClassificationOrDistinctInterfaces() {
        val wifi = LocalAddress("192.168.1.5", "wlan0", LocalAddressKind.WIFI)
        val vpn = LocalAddress("10.0.0.2", "tun0", LocalAddressKind.VPN)
        val sameIpOtherInterface = LocalAddress("192.168.1.5", "usb0", LocalAddressKind.OTHER)
        val actual = LocalAddressRules.merge(listOf(vpn, wifi, wifi), listOf(
            wifi.copy(kind = LocalAddressKind.OTHER), vpn.copy(kind = LocalAddressKind.OTHER), sameIpOtherInterface,
        ))
        assertEquals(listOf(wifi, sameIpOtherInterface, vpn), actual)
    }

    @Test fun keepsPublicAndCarrierIpv4ButRejectsAddressesThatCannotBeShared() {
        val accepted = listOf("192.168.1.2", "100.64.1.2", "203.0.113.2")
        val excluded = listOf("127.0.0.1", "127.1.2.3", "169.254.1.2", "0.0.0.0", "224.0.0.1", "239.1.2.3", "::1", "999.1.2.3")
        val actual = LocalAddressRules.merge(emptyList(), (accepted + excluded).map {
            LocalAddress(it, "unknown0", LocalAddressKind.OTHER)
        })
        assertEquals(accepted.toSet(), actual.map { it.ip }.toSet())
        assertTrue(actual.all { it.kind == LocalAddressKind.OTHER })
    }

    @Test fun orderingIsStableAndLanCandidatesPrecedeVpnAndCellular() {
        val input = listOf(
            LocalAddress("10.0.0.1", "tun0", LocalAddressKind.VPN),
            LocalAddress("192.168.1.10", "wlan0", LocalAddressKind.WIFI),
            LocalAddress("100.64.0.1", "rmnet0", LocalAddressKind.CELLULAR),
            LocalAddress("192.168.1.2", "wlan0", LocalAddressKind.WIFI),
            LocalAddress("192.168.9.1", "ap0", LocalAddressKind.OTHER),
            LocalAddress("172.16.0.2", "eth0", LocalAddressKind.ETHERNET),
        )
        val actual = LocalAddressRules.merge(input, emptyList())
        assertEquals(actual, LocalAddressRules.merge(input.reversed(), emptyList()))
        assertEquals(listOf("192.168.1.2", "192.168.1.10", "172.16.0.2", "192.168.9.1", "10.0.0.1", "100.64.0.1"), actual.map { it.ip })
        assertEquals(LocalAddressKind.OTHER, actual.single { it.interfaceName == "ap0" }.kind)
    }

    @Test fun missingLinkInterfaceNameCanUseAnUnambiguousInterfaceObservation() {
        val wifi = LocalAddress("192.168.1.8", "", LocalAddressKind.WIFI)
        val fallback = wifi.copy(interfaceName = "wlan0", kind = LocalAddressKind.OTHER)
        assertEquals(listOf(wifi.copy(interfaceName = "wlan0")), LocalAddressRules.merge(listOf(wifi), listOf(fallback)))
    }

    @Test fun secondaryVpnAndCellularAddressesStayOutOfLanCandidates() {
        val networks = listOf(
            LocalAddress("10.10.0.1", "tun0", LocalAddressKind.VPN),
            LocalAddress("100.64.0.1", "rmnet0", LocalAddressKind.CELLULAR),
        )
        val aliases = listOf(
            LocalAddress("192.168.50.1", "tun0", LocalAddressKind.OTHER),
            LocalAddress("100.64.0.2", "rmnet0", LocalAddressKind.OTHER),
        )
        val actual = LocalAddressRules.merge(networks, aliases)
        assertEquals(LocalAddressKind.VPN, actual.single { it.ip == "192.168.50.1" }.kind)
        assertEquals(LocalAddressKind.CELLULAR, actual.single { it.ip == "100.64.0.2" }.kind)
        assertTrue(actual.none { it.kind == LocalAddressKind.OTHER })
    }

    @Test fun multipleWifiIpsKeepTheirTransportAndVpnWinsConflictingInterfaceEvidence() {
        val wifi = LocalAddress("192.168.1.5", "wlan0", LocalAddressKind.WIFI)
        val secondary = wifi.copy(ip = "192.168.1.6", kind = LocalAddressKind.OTHER)
        val actual = LocalAddressRules.merge(listOf(wifi), listOf(wifi, secondary))
        assertEquals(listOf(wifi, secondary.copy(kind = LocalAddressKind.WIFI)), actual)
        val conflicting = listOf(wifi, wifi.copy(kind = LocalAddressKind.VPN))
        val vpn = LocalAddressRules.merge(conflicting, listOf(secondary))
        assertTrue(vpn.all { it.kind == LocalAddressKind.VPN })
        assertEquals(vpn, LocalAddressRules.merge(conflicting.reversed(), listOf(secondary)))
    }
}
