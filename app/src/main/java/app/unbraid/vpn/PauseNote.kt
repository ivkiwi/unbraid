package app.unbraid.vpn

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import java.io.File

/**
 * A pause outlives its process: Android restarts a paused service it ended with no command, which would connect. So the
 * pause is noted with its end on the clock that counts sleep ([SystemClock.elapsedRealtime]), which holds only in the
 * boot it was noted in, and the note goes whenever the pause ends. A plain file, so a failed write or removal is seen.
 */
object PauseNote {
    /**
     * Whether a pause noted in boot [noted], ending at [wake] (0: only when resumed by hand), still holds in boot [boot]
     * at [now]. An unknown boot (-1) never matches: the service connects rather than stay paused on a guess.
     */
    fun holds(noted: Int?, wake: Long, boot: Int, now: Long) = noted != null && noted == boot && boot >= 0 && (wake == 0L || now < wake)

    private fun file(ctx: Context) = File(ctx.filesDir, "pause")
    /** -1 when Android can't tell, or fails to say. */
    private fun boot(ctx: Context) = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    /** A [forget] that failed: the old note may still be read until [retry] removes it. A new [note] replaces it. */
    @Volatile var stuck = false
        private set

    /**
     * Paused until [wake] (elapsed clock, 0: until resumed), since [at] (wall clock). False if not written, or in a boot
     * that can't be told (-1), whose note a restart would never trust: the service then doesn't pause.
     */
    @Synchronized fun note(ctx: Context, wake: Long, at: Long, boot: Int = boot(ctx)): Boolean =
        (boot >= 0 && runCatching { file(ctx).writeText("$boot $wake $at") }.isSuccess).also { if (it) stuck = false }

    /**
     * The pause ended (resumed, disconnected, refused, the service gone). A note that can't be deleted is emptied, which
     * reads as none; false if it may still be read, when a restart would pause again ([stuck]).
     */
    @Synchronized fun forget(ctx: Context): Boolean =
        file(ctx).let { !it.exists() || it.delete() || runCatching { it.writeText("") }.isSuccess }.also { stuck = !it }

    /** Removes a note [forget] couldn't, unless a pause noted since replaced it; true once none is left to remove. */
    @Synchronized fun retry(ctx: Context): Boolean = !stuck || forget(ctx)

    /** The noted pause, when it still holds: its end ([note]'s wake) and when it began; null when none does. */
    fun left(ctx: Context): Pair<Long, Long>? = runCatching {
        val (noted, wake, at) = file(ctx).readText().split(' ').map(String::toLong)
        if (holds(noted.toInt(), wake, boot(ctx), SystemClock.elapsedRealtime())) wake to at else null
    }.getOrNull()
}
