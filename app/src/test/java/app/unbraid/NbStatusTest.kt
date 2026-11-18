package app.unbraid

import app.unbraid.vpn.NbStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NbStatusTest {
    @Test fun readsTheEngineStatus() {
        val s = NbStatus.parse("""{"state":"running","ip":"100.101.0.9","fqdn":"phone.netbird.cloud","network":"100.101.0.0/16","management":true,
            "routes":["192.168.1.0/24"],"peers":[{"fqdn":"nas.netbird.cloud","ip":"100.101.0.7","connected":true,"relayed":false},{"fqdn":"","ip":"100.101.0.8"}],
            "exit_nodes":[{"id":"exit-home","name":"box.netbird.cloud","selected":true},{"id":"exit-2"}],"extra":1}""")
        assertTrue(s.running)
        assertEquals(listOf("100.101.0.0/16", "192.168.1.0/24"), s.networks)
        assertEquals(listOf("nas.netbird.cloud", "phone.netbird.cloud"), s.names)
        assertEquals(listOf("box", "exit-2"), s.exitNodes.map { it.label })
    }

    @Test fun onlyNamesUnderItsOwnDomain() {
        // A peer named "accounts" in a domain an admin set to google.com must not take accounts.google.com.
        val s = NbStatus.parse("""{"state":"running","fqdn":"phone.netbird.cloud.","peers":[{"fqdn":"nas.netbird.cloud"},{"fqdn":"accounts.google.com"},{"fqdn":"x.notnetbird.cloud"}]}""")
        assertEquals(listOf("nas.netbird.cloud", "phone.netbird.cloud"), s.names)
        assertEquals("netbird.cloud", s.domain)
        // No usable own name (a single label, or none): no names at all.
        assertEquals(emptyList<String>(), NbStatus.parse("""{"state":"running","fqdn":"phone","peers":[{"fqdn":"nas.netbird.cloud"}]}""").names)
    }

    @Test fun aFailedStartKeepsItsError() {
        val s = NbStatus.parse("""{"state":"failed","error":"login: rpc error: code = PermissionDenied","management":false,"peers":null,"exit_nodes":null,"routes":null}""")
        assertEquals("failed", s.state)
        assertEquals("login: rpc error: code = PermissionDenied", s.error)
        assertTrue(s.peers.isEmpty() && s.exitNodes.isEmpty() && s.routes.isEmpty())
    }

    @Test fun unreadableIsOff() {
        for (raw in listOf(null, "", "nonsense", "{\"state\":")) assertFalse(NbStatus.parse(raw).running)
        assertEquals("off", NbStatus.parse(null).state)
    }
}
