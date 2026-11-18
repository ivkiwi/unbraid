package app.unbraid

import app.unbraid.data.Setup
import app.unbraid.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

/**
 * What a refused change after a restore does to the running VPN: it stops only an engine that runs a setup from before
 * the latest restore, which exists nowhere else. The restore's number travels in the setup it put in the store
 * ([Setup.restore]), so a reload reads the setup and where it comes from in one read. A separate counter could be read
 * out of step with the setup: a restore published before its count kept the old setup after a refusal; a retry, an edit
 * or the same backup again could leave the running engine's number behind and stop a healthy VPN.
 */
class RestoreAckTest {
    @get:Rule val tmp = TemporaryFolder()

    /** The engine: the setup it runs and that setup's restore number; [refusedStops] is the service's decision on a refused one. */
    private class Engine(var runs: Setup) {
        var ran = runs.restore
        fun install(s: Setup) { runs = s; ran = s.restore }
        fun refusedStops(s: Setup): Boolean { ran = app.unbraid.vpn.ranRestore(ran, runs, s); return ran != s.restore }
    }

    /** As the restore numbers what it stores ([s], a backup's setup made this phone's) and keeps it with its edits. */
    private fun restored(s: Setup, from: Setup) = s.copy(restore = from.restore + 1, fromRestore = s.copy(restore = 0, fromRestore = null))

    @Test fun theSameBackupAgainKeepsTheVpn() {
        val a = Setup(defaultRoute = "a")
        val b1 = restored(Setup(defaultRoute = "b"), a)
        val e = Engine(a)
        e.install(b1)
        val b2 = restored(Setup(defaultRoute = "b"), b1)
        assertNotEquals(b1, b2) // the watcher sees it and the reload runs it, the engine untouched (same config)
        e.install(b2)
        assertFalse(e.refusedStops(b2.copy(defaultRoute = "broken"))) // an edit after it carries its number
    }

    /**
     * The same backup restored again, then an edit quick enough that the watcher saw only the edit: the engine never
     * installed the second restore, but runs what it stored (with an earlier time for apps still to be installed), so a
     * refused edit keeps the VPN and the engine counts as running that restore. Another backup's is still stopped.
     */
    @Test fun theSameBackupAgainUnseenByTheWatcherKeepsTheVpn() {
        val a = Setup(defaultRoute = "a")
        val b1 = restored(Setup(defaultRoute = "b", waiting = setOf("x"), waitingUntil = 1), a)
        val e = Engine(a)
        e.install(b1)
        val b2 = restored(Setup(defaultRoute = "b", waiting = setOf("x"), waitingUntil = 2), b1) // no install(b2)
        assertFalse(e.refusedStops(b2.copy(defaultRoute = "broken")))
        assertEquals(b2.restore, e.ran)
        assertFalse(e.refusedStops(b2.copy(defaultRoute = "broken2"))) // and stays so
        assertTrue(e.refusedStops(restored(Setup(defaultRoute = "c"), b2).copy(defaultRoute = "broken")))
    }

    /**
     * The app named in the backup is installed: the first restore's wait for it was cleared and the engine ran that; the
     * second restore waits for it again. The same routes all the same, so a refused edit keeps the VPN; a backup that
     * routes an app otherwise is another setup and is still stopped.
     */
    @Test fun aWaitClearedSinceTheFirstRestoreStillCountsAsTheSame() {
        val b = Setup(defaultRoute = "b", apps = mapOf("x" to "r"))
        val b1 = restored(b.copy(waiting = setOf("x"), waitingUntil = 1), Setup(defaultRoute = "a"))
        val e = Engine(b1.copy(waiting = emptySet(), waitingUntil = 0)) // the app list cleared the wait, the engine ran it
        val b2 = restored(b.copy(waiting = setOf("x"), waitingUntil = 2), b1) // no install(b2)
        assertFalse(e.refusedStops(b2.copy(defaultRoute = "broken")))
        val other = restored(b.copy(apps = mapOf("x" to "s"), waiting = setOf("x"), waitingUntil = 3), b2)
        assertTrue(e.refusedStops(other.copy(defaultRoute = "broken")))
    }

    /** The store publishes the restored setup before it is written: a reload that sees it sees its number with it. */
    @Test fun aRestoreSeenBeforeItIsSavedIsAlreadyNumbered() {
        val a = Setup(defaultRoute = "a")
        val e = Engine(a)
        assertTrue(e.refusedStops(restored(Setup(defaultRoute = "b"), a)))
    }

    /**
     * The number noted is the running setup's own, set with it: a retry that read the setup before the same backup was
     * restored again, answered after an edit, can't leave it behind another setup's. (The service drops a retry whose
     * setup the store no longer holds.)
     */
    @Test fun theNoteIsTheRunningSetupsOwn() {
        val a = Setup(defaultRoute = "a")
        val b1 = restored(Setup(defaultRoute = "b"), a)
        val b2 = restored(b1, b1)
        val e = Engine(b1)
        e.install(b2)
        e.install(b2.copy(defaultRoute = "edit")) // an edit the engine took
        assertFalse(e.refusedStops(b2.copy(defaultRoute = "edit2")))
        assertEquals(b2.restore, e.ran)
    }

    /** The number survives every edit through the store and is never saved: a new process starts from 0, with no engine. */
    @Test fun theStoreCarriesTheNumberAndNeverSavesIt() {
        val dir = tmp.newFolder()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = Store(dir) { key }
        assertTrue(store.update { it.copy(restore = it.restore + 1, fromRestore = it) })
        store.edit { it.copy(defaultRoute = "x") }
        store.flush()
        assertEquals(1L, store.setup.value.restore)
        assertEquals("x", store.setup.value.defaultRoute)
        assertTrue(store.setup.value.fromRestore != null)
        Store(dir) { key }.setup.value.let { assertEquals(0L, it.restore); assertEquals(null, it.fromRestore) }
    }

    @Test fun theServiceAndTheRestoreUseTheNumber() {
        val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        assertFalse(vpn.contains("restores"))
        // Under the lock, in the refusal: a restore the watcher skipped counts as running when what runs is what it stored.
        assertTrue(vpn.contains("ranRestores = ranRestore(ranRestores, applied, setup)\n        if (restored && ranRestores != setup.restore) teardown(RESTORE_REFUSED)"))
        assertEquals(2, Regex("""val setup = store\.setup\.value\n\s+val seen = setup\.restore""").findAll(vpn).count()) // connect and reload
        val apply = java.io.File("src/main/java/app/unbraid/ui/Backup.kt").readText().substringAfter("fun apply() {")
        assertTrue(apply.contains("s.restoredFrom(c.setup, keepAs, System.currentTimeMillis(), c.keys)?.let { it.copy(restore = s.restore + 1, fromRestore = it) }?.also { target = it }"))
        assertFalse(apply.contains("UnbraidVpn.restore"))
    }
}
