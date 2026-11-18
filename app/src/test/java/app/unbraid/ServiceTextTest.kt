package app.unbraid

import app.unbraid.data.AuthException
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.vpn.blockedText
import app.unbraid.vpn.startingLockdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTextTest {
    private val nl = Route("nl", Location("nord", 153, "NL", "Netherlands"))
    private val setup = Setup(routes = listOf(nl), defaultRoute = "nl")

    /** A default route that is gone is refused before any provider is asked; Direct as the default is a real choice. */
    @Test fun missingDefaultIsFoundBeforeConnecting() {
        assertFalse(setup.defaultMissing)
        assertFalse(setup.copy(defaultRoute = DIRECT).defaultMissing)
        assertTrue(setup.copy(defaultRoute = "gone").defaultMissing)
        assertTrue(setup.copy(routes = emptyList()).defaultMissing)
    }

    /** Apps on a route that no longer exists are said to be blocked, beside any route waiting for a server. */
    @Test fun appsOnAMissingRouteAreNamed() {
        val gone = "Apps on a route that no longer exists are blocked. Choose a route for them in unbraid."
        assertNull(blockedText(setup, emptyMap()))
        assertEquals(gone, blockedText(setup.copy(apps = mapOf("com.a" to "gone")), emptyMap()))
        assertNull(blockedText(setup.copy(apps = mapOf("com.a" to DIRECT, "com.b" to "nl")), emptyMap()))
        val both = blockedText(setup.copy(apps = mapOf("com.a" to "gone")), mapOf("nl" to AuthException("Sign in again")))!!
        assertTrue(both.startsWith("Apps on ${nl.name} are blocked: Sign in again"))
        assertTrue(both.endsWith(gone))
    }

    /** Release logs keep only the class of a foreign error, which can echo a key with no name; the app's own text stays. */
    @Test fun releaseLogsNoForeignErrorText() {
        val key = "aGVsbG8gd29ybGQgdGhpcyBpcyBhIGtleQ"
        val foreign = IllegalStateException("bad value $key")
        assertEquals("IllegalStateException", app.unbraid.vpn.logLine(foreign, debug = false))
        assertFalse(app.unbraid.vpn.logText("parse $key", debug = false).contains(key))
        assertEquals("Fail: The connection engine couldn't start.", app.unbraid.vpn.logLine(app.unbraid.vpn.Fail("The connection engine couldn't start."), debug = false))
        assertTrue(app.unbraid.vpn.logLine(foreign, debug = true).startsWith("IllegalStateException: bad value"))
    }

    /**
     * Block connections without VPN turned on while the connection was starting: the tunnel was built without it, so the
     * watcher starts from that and sees the change Android now reports (and the converse).
     */
    @Test fun lockdownWatchStartsFromTheBuiltTunnel() {
        val setup = Setup()
        fun built(lockdown: Boolean) = app.unbraid.vpn.Compiler.Input(setup, emptyMap(), "app.unbraid", lockdown, "/tmp/ts", "/tmp/cache.db")
        assertFalse(startingLockdown(built(false), android = true))
        assertTrue(startingLockdown(built(true), android = false))
        assertTrue(startingLockdown(null, android = true))
    }

    /**
     * Unreadable settings keep apps offline even when the engine can't get ready (its fake-IP block not saved on a full
     * disk, no room for its crash log): the service can't run here, so this checks that the hold puts Android's own
     * blocking interface up before anything that can fail, which needs neither the engine nor a file, with its own DNS
     * server so no lookup goes to the network's resolver; that no failure after it reaches the connect's teardown; and
     * that a start out of the hold that fails goes back to it with that interface kept, instead of tearing it down, also
     * when Android refuses a new one then.
     */
    @Test fun theHoldNeedsNoEngineNorFile() {
        val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        val hold = vpn.substringAfter("private fun hold(").substringBefore("\n    }\n")
        // Refused while one is up (back from a failed start), the hold keeps that one and goes on; with none it fails.
        assertTrue(hold.indexOf("runCatching { blockingTun() }.onFailure { if (tun == null) throw it;") in 0 until hold.indexOf("runCatching {\n"))
        val ready = hold.substringAfter("runCatching {\n").substringBefore("}.onFailure {")
        listOf("engineSetup(this)", "CommandServer(", "fakeBlock()", "store.fakeBlock = block", "Libbox.checkConfig(json)", "startOrReloadService(").forEach { assertTrue(it, ready.contains(it)) }
        val tun = vpn.substringAfter("private fun blockingTun() {").substringBefore("\n    }\n")
        assertTrue(tun.contains("addRoute(\"0.0.0.0\", 0)"))
        assertTrue(tun.indexOf("addDnsServer(\"172.19.0.2\")") in 0 until tun.indexOf("establish()"))
        listOf("store.", "cache.db", "Libbox", "fakeBlock").forEach { assertFalse(it, tun.contains(it)) }
        // The service's first step can't fail before a command is read.
        assertTrue(vpn.substringAfter("override fun onCreate() {").substringBefore("for ((intent, locked) in inbox)").contains("runCatching { engineSetup(this@UnbraidVpn) }"))
        // Out of the hold: a failed start keeps the interface and holds again.
        assertTrue(hold.contains("connect(held = true)"))
        val connect = vpn.substringAfter("private fun connect(").substringBefore("\n    }\n")
        val failed = connect.substringAfter("} catch (e: Exception) {")
        assertTrue(failed.indexOf("if (held && runCatching { stopEngine(keepTun = true); hold(") in 0 until failed.indexOf("teardown("))
        assertTrue(vpn.contains("if (!keepTun) { runCatching { tun?.close() }; tun = null }"))
    }
}
