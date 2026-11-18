package app.unbraid

import app.unbraid.vpn.PauseNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PauseNoteTest {
    /**
     * Paused for an hour, then Android ended the process and restarted the service with no command: the pause noted in
     * this boot holds until its end, one until resumed by hand holds as long as the boot does, and nothing holds once
     * the end has passed, after a reboot (the elapsed clock starts again), or when the boot can't be told.
     */
    @Test fun aPauseHoldsOnlyInItsBootUntilItsEnd() {
        val wake = 3_600_000L + 50_000L
        assertTrue(PauseNote.holds(noted = 7, wake = wake, boot = 7, now = 110_000L)) // a minute in
        assertFalse(PauseNote.holds(noted = 7, wake = wake, boot = 7, now = wake))
        assertTrue(PauseNote.holds(noted = 7, wake = 0, boot = 7, now = Long.MAX_VALUE))
        assertFalse(PauseNote.holds(noted = 7, wake = wake, boot = 8, now = 110_000L))
        assertFalse(PauseNote.holds(noted = -1, wake = 0, boot = -1, now = 110_000L))
        assertFalse(PauseNote.holds(noted = null, wake = 0, boot = 7, now = 110_000L))
    }

    /** A context whose files are in [dir]; Android's stubs read boot 0 and elapsed time 0 here. */
    private fun context(dir: java.io.File) = object : android.content.ContextWrapper(null) { override fun getFilesDir() = dir }

    /**
     * Noted, read back, forgotten; a note that can't be deleted is emptied and reads as none; one that can't be removed
     * nor emptied (a directory in its place) is said so, never reported gone.
     */
    @Test fun aNoteIsWrittenAndRemovedOrSaysItWasNot() {
        val dir = kotlin.io.path.createTempDirectory().toFile().apply { deleteOnExit() }
        val ctx = context(dir)
        assertNull(PauseNote.left(ctx))
        // A boot that can't be told: a restart would never trust the note, so none is made and the service doesn't pause.
        assertFalse(PauseNote.note(ctx, 60_000L, 1234L, boot = -1))
        assertFalse(java.io.File(dir, "pause").exists())
        // Read guarded, so a failure to read it is -1 too, never an exception past the service's "Not paused".
        val src = java.io.File("src/main/java/app/unbraid/vpn/PauseNote.kt").readText()
        assertTrue(src.contains("private fun boot(ctx: Context) = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)"))
        assertTrue(PauseNote.note(ctx, 60_000L, 1234L))
        assertEquals(60_000L to 1234L, PauseNote.left(ctx))
        assertTrue(PauseNote.forget(ctx))
        assertNull(PauseNote.left(ctx))
        assertTrue(PauseNote.forget(ctx)) // nothing to remove
        java.io.File(dir, "pause").writeText("")
        assertNull(PauseNote.left(ctx)) // emptied: none
        java.io.File(dir, "pause").delete()
        java.io.File(dir, "pause/x").apply { parentFile!!.mkdirs(); writeText("1") }
        assertFalse(PauseNote.note(ctx, 60_000L, 1234L))
        assertFalse(PauseNote.forget(ctx))
        assertNull(PauseNote.left(ctx))
    }

    /**
     * A note that wouldn't go is retried until it goes; a pause noted meanwhile replaces it, and the retry leaves that
     * one alone, so a restart by Android still finds the new pause.
     */
    @Test fun aStuckNoteIsRetriedButANewPauseIsKept() {
        val dir = kotlin.io.path.createTempDirectory().toFile().apply { deleteOnExit() }
        val ctx = context(dir)
        val pause = java.io.File(dir, "pause")
        fun block() { pause.delete(); java.io.File(pause, "x").apply { parentFile!!.mkdirs(); writeText("1") } }
        fun unblock() { pause.deleteRecursively() }
        assertTrue(PauseNote.note(ctx, 60_000L, 1L))
        block()
        assertFalse(PauseNote.forget(ctx))
        assertTrue(PauseNote.stuck)
        assertFalse(PauseNote.retry(ctx)) // still there: the loop waits and tries again
        unblock()
        assertTrue(PauseNote.retry(ctx))
        assertFalse(PauseNote.stuck)
        block()
        assertFalse(PauseNote.forget(ctx))
        unblock()
        assertTrue(PauseNote.note(ctx, 120_000L, 2L)) // a new pause before the retry came round
        assertFalse(PauseNote.stuck)
        assertTrue(PauseNote.retry(ctx))
        assertEquals(120_000L to 2L, PauseNote.left(ctx))
    }

    /**
     * The service can't run here, so this checks the glue: the pause is noted before it shows, a start with no command
     * consults the note before it connects, and every end of a pause (resume, disconnect, refusal, the service gone)
     * forgets it on the way, through [cancelResume].
     */
    @Test fun theServiceKeepsAPauseAcrossARestart() {
        val vpn = java.io.File("src/main/java/app/unbraid/vpn/UnbraidVpn.kt").readText()
        val pause = vpn.substringAfter("private fun pause(minutes: Int) {").substringBefore("\n    }\n")
        // Noted before the engine stops; not noted, the VPN stays on and the user is told.
        val noted = pause.indexOf("if (!PauseNote.note(this, wake, at)) {")
        assertTrue(noted in 0 until pause.indexOf("AfterUpdate.off(this)"))
        assertTrue(noted < pause.indexOf("stopEngine()") && noted < pause.indexOf("paused(wake, at, lockdown, alwaysOn)"))
        val refused = pause.substring(noted).substringBefore("\n        }")
        assertTrue(refused.contains("\"Not paused\", 6)") && refused.trimEnd().endsWith("return"))
        val handle = vpn.substringAfter("private fun handle(intent: Intent?, locked: Boolean) {").substringBefore("\n    }\n")
        assertTrue(handle.indexOf("if (intent == null) PauseNote.left(this)?.let") in 0 until handle.indexOf("connect(intent?.getBooleanExtra"))
        val cancel = vpn.substringAfter("private fun cancelResume() {").substringBefore("\n    }\n")
        assertTrue(cancel.contains("if (!PauseNote.forget(this)) {"))
        // Not removed: the user is told, and one loop tries again every 30 s until it goes or a new pause replaces it.
        assertTrue(cancel.contains("\"Pause not cleared\", 6)") && cancel.contains("retryForget(applicationContext)"))
        val retry = vpn.substringAfter("private fun retryForget(ctx: Context) {").substringBefore("\n        }\n")
        assertTrue(retry.trimStart().startsWith("if (!forgetting.compareAndSet(false, true)) return"))
        assertTrue(retry.contains("while (!PauseNote.retry(ctx)) kotlinx.coroutines.delay(30_000)"))
        // The flag drops before the second look: a failure that found it still up is picked up, not lost.
        assertTrue(retry.indexOf("forgetting.set(false)") in 0 until retry.indexOf("if (PauseNote.stuck) retryForget(ctx)"))
        // Every way a pause ends goes through cancelResume: a resume (connect), a stop (teardown), a refused start, onDestroy.
        assertEquals(1, Regex("""PauseNote\.forget\(""").findAll(vpn).count())
        assertTrue(vpn.substringAfter("private fun connect(").substringBefore("state.value = VpnState(VpnState.Phase.Connecting)").contains("cancelResume()"))
        assertTrue(vpn.substringAfter("private fun teardown(error: String?) {").substringBefore("\n    }\n").contains("cancelResume()"))
    }
}
