package io.github.chenxiex.calibrecloud.tasks.background

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/**
 * Keeps only the CPU running while an explicit application state lasts, so a device that suspends
 * with the screen on does not stall that work until its next wakeup. PARTIAL_WAKE_LOCK never keeps
 * the screen on or delays its timeout. Acquire and release follow the state, never a fixed duration;
 * the caller guarantees the release, including on cancellation.
 *
 * [counted]: every [hold] is paired with one [release]; otherwise one [release] ends all holds.
 */
class CpuAwake(context: Context, tag: String, counted: Boolean) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CalibreCloud:$tag")
        .apply { setReferenceCounted(counted) }

    // Released by the state change that ends the work, not by an estimated time.
    @SuppressLint("WakelockTimeout")
    @Synchronized fun hold() = lock.acquire()

    @Synchronized fun release() {
        if (lock.isHeld) lock.release()
    }

    suspend fun during(block: suspend () -> Unit) {
        hold()
        try { block() } finally { release() }
    }
}
