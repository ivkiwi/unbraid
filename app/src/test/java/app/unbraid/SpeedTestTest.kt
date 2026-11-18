package app.unbraid

import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.stats.SpeedTest
import app.unbraid.vpn.TsNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTestTest {
    private val nodes = listOf(TsNode("nas", "100.64.0.5", true, "nStable1"), TsNode("pi", "100.64.0.6", true, "nStable2"))

    /** After testing another node, Tailscale goes back to the node it used, else the routed one, else to no exit at all. */
    @Test fun theExitNodeComesBackOrNoneIsUsed() {
        assertEquals("", SpeedTest.restoreTo(null, "100.64.0.5\nnStable1", nodes)) // no route on Tailscale's exit any more
        // The route's setting now, by address, ID or a unique name.
        assertEquals("nStable2", SpeedTest.restoreTo("100.64.0.6", "", nodes))
        assertEquals("nStable1", SpeedTest.restoreTo("NAS", null, nodes))
        assertEquals("nStable2", SpeedTest.restoreTo("pi.tail1234.ts.net.", "", nodes))
        // The route changed during the test: the new setting wins over the note from the start.
        assertEquals("nStable2", SpeedTest.restoreTo("100.64.0.6", "100.64.0.5\nnStable1", nodes))
        // The node isn't listed now: the note helps only for the same setting.
        assertEquals("nStable1", SpeedTest.restoreTo("nas", "nas\nnStable1", emptyList()))
        assertNull(SpeedTest.restoreTo("nas", "pi\nnStable2", emptyList()))
        // A name two nodes share is no match; not known at all: no exit node meanwhile (null), never the tested one.
        assertNull(SpeedTest.restoreTo("nas", "", nodes + TsNode("nas", "100.64.0.9", true, "nStable3")))
        assertNull(SpeedTest.restoreTo("nas", "nas\nnStable1", nodes + TsNode("nas", "100.64.0.9", true, "nStable3")))
        assertNull(SpeedTest.restoreTo("gone", "", nodes))
    }

    /** A saved node the engine refused (B) is never where a test of another node (C) ends: the engine still runs A. */
    @Test fun aNodeTestSwitchesBackToTheNodeTheEngineRuns() {
        fun on(ip: String) = Setup(routes = listOf(Route("ts", tsExit = ip)), apps = mapOf("a" to "ts"))
        val running = on("100.64.0.5"); val saved = on("100.64.0.6") // A runs; the edit to B was refused
        val hint = SpeedTest.routedNow(running, saved)?.let { r -> SpeedTest.restoreTo(r, null, nodes)?.let { "$r\n$it" } }
        assertEquals("nStable1", SpeedTest.restoreTo(SpeedTest.routedNow(running, saved), hint, nodes))
        assertEquals("100.64.0.5", SpeedTest.routedNow(running, saved))
        assertEquals("100.64.0.6", SpeedTest.routedNow(null, saved)) // no engine yet: the saved setting is all there is
    }

    /** The switch back reads what runs and switches under the service's lock: no engine starts in between to be put on the old node. */
    @Test fun theSwitchBackHoldsTheServiceLock() {
        val back = java.io.File("src/main/java/app/unbraid/stats/SpeedTest.kt").readText().substringAfter("private fun switchBack()").substringBefore("\n    }\n")
        assertTrue(back, back.trimStart().startsWith("= UnbraidVpn.withRunning<Boolean> { running ->\n        if (Unbraid.store.tsTestFrom == null) return@withRunning true"))
        assertTrue(back, "routedNow(running," in back && "UnbraidVpn.running" !in back && "switchTo(" in back && "setTailscaleExitNode" !in back)
        assertTrue("lock.withLock { f(running) }" in java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText())
        // The switch to the tested node too, and each switch has a deadline that cancels the call in the engine.
        val src = java.io.File("src/main/java/app/unbraid/stats/SpeedTest.kt").readText()
        assertTrue(Regex("withRunning<Boolean\\?> \\{ running ->\\s+if \\(moved\\(\\)\\) return@withRunning null\\s+if \\(stop\\(\\)\\) return@withRunning false").containsMatchIn(src))
        assertEquals(1, Regex("setTailscaleExitNode\\(").findAll(src).count())
        // The switch-back note is on disk before the switch to the tested node, or there is no switch.
        val to = src.substringAfter("val ok = UnbraidVpn.withRunning<Boolean?>").substringBefore("switchTo(node.id)")
        assertTrue(to, Regex("if \\(Unbraid\\.store\\.tsTestFrom == null\\) \\{ unsaved = true; return@withRunning false \\}").containsMatchIn(to.substringAfter("Unbraid.store.tsTestFrom = ")))
        assertTrue(Regex("if \\(unsaved\\) \\{ set \\{ it\\.copy\\(phase = Phase\\.Failed, error = \".*\"\\) \\}; return@execute \\}").containsMatchIn(src))
        val switch = src.substringAfter("private fun switchTo(").substringBefore("\n    }\n")
        assertTrue(switch, "call.get(SWITCH_MS" in switch && "c.disconnect()" in switch)
    }

    /** After an engine restart the switch back waits for the new engine's own status to show its node in use, and never takes a stale one for it. */
    @Test fun aNewEngineKeepsItsOwnNode() {
        val b = TsNode("b", "100.64.0.6", true, "nB")
        val c = TsNode("c", "100.64.0.9", true, "nC")
        val r = "100.64.0.6"
        assertEquals(true, SpeedTest.ownNode(3, 4, 4, r, "nB", listOf(b.copy(inUse = true), c))) // its status shows B: back
        assertEquals(true, SpeedTest.ownNode(null, 1, 1, r, "nB", listOf(b.copy(inUse = true)))) // a killed app's switch, same
        assertEquals(false, SpeedTest.ownNode(3, 4, 3, r, "nB", listOf(b.copy(inUse = true), c))) // the old engine's status: wait
        assertEquals(false, SpeedTest.ownNode(3, 4, 3, r, null, listOf(c.copy(inUse = true)))) // nor clear C on a stale one
        assertNull(SpeedTest.ownNode(3, 4, 4, r, "nB", listOf(b, c.copy(inUse = true)))) // still on the tested C: switch back
        assertNull(SpeedTest.ownNode(3, 4, 4, r, null, listOf(c.copy(inUse = true)))) // B not a peer yet: off C, then again
        assertNull(SpeedTest.ownNode(3, 4, 4, null, "", listOf(c.copy(inUse = true)))) // no node of its own: the saved C goes
        assertNull(SpeedTest.ownNode(4, 4, 3, r, "nB", listOf(b, c))) // the engine the test switched: the usual way
    }

    /** A test through Tailscale's route measures nothing while the switch back is not sure; a combined run leaves only that leg out. */
    @Test fun anUnsureSwitchBackSkipsOnlyTailscale() {
        val src = java.io.File("src/main/java/app/unbraid/stats/SpeedTest.kt").readText()
        assertTrue("if (Unbraid.store.tsTestFrom != null && !switchBack()) { set { it.copy(phase = Phase.Failed, error = NOT_BACK) }; return@execute }" in src)
        val all = src.substringAfter("fun startAll(").substringBefore("parallel(_all.value")
        assertTrue(all, "!runCatching { tsq.submit<Boolean> { switchBack() }.get() }.getOrDefault(false)" in all)
        assertTrue(all, Regex("legs\\.filter \\{ UnbraidVpn\\.tags\\[it\\] == Compiler\\.TS_TAG \\}\\.forEach \\{ e -> leg\\(e\\) \\{ it\\.copy\\(error = ").containsMatchIn(all))
        assertTrue("tsExitNodes = nodes, tsEngine = starts," in java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText())
    }
}
