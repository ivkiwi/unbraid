package app.unbraid

import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.ui.nbFailed
import app.unbraid.ui.offlineExit
import app.unbraid.ui.routeWarning
import app.unbraid.vpn.TsNode
import app.unbraid.vpn.VpnState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A route through a Tailscale exit device says so when that device is offline, and only then. */
class OfflineExitTest {
    private val ip = "100.64.0.9"
    private val route = Route("ts", tsExit = ip)
    private fun vpn(online: Boolean, state: String = "Running", host: String = ip) = VpnState(
        tsState = state, servers = mapOf("ts" to Server("home-mac", host, host, "", 0)), tsExitNodes = listOf(TsNode("home-mac", ip, online)),
    )

    @Test fun anOfflineDeviceIsNamed() = assertEquals("home-mac", offlineExit(vpn(online = false), route)?.name)

    @Test fun onlyWhenItIsReallyOffline() {
        assertNull(offlineExit(vpn(online = true), route))
        // Tailscale not running yet (signing in, starting): it knows nothing about the device, so nothing is claimed.
        assertNull(offlineExit(vpn(online = false, state = "Starting"), route))
        // The route doesn't use that device now (it was just changed and not applied yet).
        assertNull(offlineExit(vpn(online = false, host = "100.64.0.10"), route))
        // A route of a VPN provider, or none.
        assertNull(offlineExit(vpn(online = false), Route("it")))
        assertNull(offlineExit(vpn(online = false), null))
    }

    /** A NetBird exit route says so when NetBird failed (a refused sign-in), and only then. */
    @Test fun aFailedNetbirdIsSaid() {
        val nb = Route("nb", nbExit = "exit-home")
        fun state(st: String, runs: Boolean = true) = VpnState(nb = app.unbraid.vpn.NbStatus(state = st),
            servers = if (runs) mapOf("nb" to Server("box", "exit-home", "", "", 0), "ts" to Server("home-mac", ip, ip, "", 0)) else emptyMap())
        assertTrue(nbFailed(state("failed"), nb))
        // A saved NetBird route the engine doesn't run (no app or website uses it) is not said to fail.
        assertFalse(nbFailed(state("failed", runs = false), nb))
        // The engine still runs what the route was before (a refused edit): not this NetBird exit.
        assertFalse(nbFailed(VpnState(nb = app.unbraid.vpn.NbStatus(state = "failed"), servers = mapOf("nb" to Server("nl1", "198.51.100.7", "198.51.100.7", "", 0))), nb))
        assertFalse(nbFailed(state("running"), nb)); assertFalse(nbFailed(state("failed"), route)); assertFalse(nbFailed(state("failed"), null))
        assertTrue(routeWarning(false, false, nbFailed = true)!!.startsWith("NetBird couldn't connect"))
    }
}
