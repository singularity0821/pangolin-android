package net.pangolin.Pangolin.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServerAddressTest {
    @Test
    fun acceptsIPv4() {
        listOf("1.1.1.1", "8.8.4.4", "192.168.1.254", "0.0.0.0", "255.255.255.255").forEach {
            assertTrue(it, DnsServerAddress.isValid(it))
        }
    }

    @Test
    fun acceptsIPv6() {
        listOf(
            "2606:4700:4700::1111",
            "2606:4700:4700:0000:0000:0000:0000:1111",
            "2001:4860:4860::8888",
            "2001:db8::1",
            "2001:DB8::ABCD",
            "fd00::53",
            "::1",
            "::",
            "1::",
            "1:2:3:4:5:6:7::",
            "::ffff:192.0.2.1",
            "64:ff9b::8.8.8.8",
            "1:2:3:4:5:6:1.2.3.4",
        ).forEach {
            assertTrue(it, DnsServerAddress.isValid(it))
        }
    }

    @Test
    fun rejectsInvalidAddresses() {
        listOf(
            "",
            "1.1.1",
            "1.1.1.1.1",
            "256.1.1.1",
            "01.1.1.1",
            "1.1.1.",
            "1..1.1",
            "1.1.1.1:53",
            ":",
            ":::",
            "1:::2",
            "1::2::3",
            ":1::2",
            "1::2:",
            "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7:8::",
            "12345::1",
            "2001:db8::g",
            "::1.2.3",
            "1:2:3:4:5:6:7:1.2.3.4",
            "1.2.3.4::",
            "[2606:4700:4700::1111]",
            "2606:4700:4700::1111%wlan0",
            "dns.example.com",
        ).forEach {
            assertFalse(it, DnsServerAddress.isValid(it))
        }
    }

    @Test
    fun formatsHostPort() {
        assertEquals("1.1.1.1:53", DnsServerAddress.toHostPort("1.1.1.1"))
        assertEquals("[2606:4700:4700::1111]:53", DnsServerAddress.toHostPort("2606:4700:4700::1111"))
    }
}
