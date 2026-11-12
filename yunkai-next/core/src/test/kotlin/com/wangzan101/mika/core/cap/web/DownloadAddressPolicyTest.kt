package com.wangzan101.mika.core.cap.web

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress

class DownloadAddressPolicyTest {
    @Test fun localAndPrivateAddressesAreRefused() {
        for (u in listOf(
            "http://localhost/x", "http://LOCALHOST./x", "http://printer.local/x", "http://a.localhost/x", "http://nas.lan/",
            "http://127.0.0.1/x", "http://127.9.9.9/", "http://10.1.2.3/", "http://172.16.0.1/", "http://172.31.255.255/",
            "http://192.168.1.1/", "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/", "http://0.0.0.0/",
            "http://[::1]/", "http://[fe80::1]/", "http://[fd00::1]/", "http://[::ffff:192.168.0.1]/", "http://224.0.0.1/",
        )) assertNotNull(u, DownloadAddressPolicy.refusal(u))
    }

    @Test fun publicAddressesPass() {
        for (u in listOf("https://example.com/a.pdf", "http://8.8.8.8/x", "https://[2606:4700::1111]/", "http://172.32.0.1/", "http://11.0.0.1/")) {
            assertNull(u, DownloadAddressPolicy.refusal(u))
        }
    }

    @Test fun sameRuleAsNavigationFirst() {
        assertNotNull(DownloadAddressPolicy.refusal("file:///data/data/x"))
        assertNotNull(DownloadAddressPolicy.refusal("intent://x#Intent;package=a.b;end"))
        assertNotNull(DownloadAddressPolicy.refusal("javascript:alert(1)"))
    }

    @Test fun namesThatResolveToThePrivateNetworkAreRefusedAtLookup() {
        val fake = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
                "rebind.example" -> listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("192.168.0.10"))
                else -> listOf(InetAddress.getByName("93.184.216.34"))
            }
        }
        val dns = DownloadAddressPolicy.guardedDns(fake)
        assertEquals(1, dns.lookup("example.com").size)
        try { dns.lookup("rebind.example"); fail("private address passed") } catch (e: DownloadAddressPolicy.BlockedAddress) { }
        try { dns.lookup("localhost"); fail("localhost passed") } catch (e: DownloadAddressPolicy.BlockedAddress) { }
    }

    @Test fun addressClasses() {
        assertTrue(DownloadAddressPolicy.blockedAddress(InetAddress.getByName("198.18.0.1")))
        assertFalse(DownloadAddressPolicy.blockedAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(DownloadAddressPolicy.blockedAddress(InetAddress.getByName("64:ff9b::a00:1"))) // NAT64 里的 10.0.0.1
    }
}
