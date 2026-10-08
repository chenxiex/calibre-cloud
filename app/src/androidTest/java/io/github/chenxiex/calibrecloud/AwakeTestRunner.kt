package io.github.chenxiex.calibrecloud

import android.content.Context
import android.os.Bundle
import android.os.PowerManager
import androidx.test.runner.AndroidJUnitRunner

/**
 * Keeps the CPU running for the whole instrumentation run. Some e-ink devices suspend with the screen on
 * whenever no wake lock is held and wake only for a periodic alarm, which froze the test process for most
 * of every minute. Production code does not depend on this lock.
 */
class AwakeTestRunner : AndroidJUnitRunner() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate(arguments: Bundle?) {
        wakeLock = (targetContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "calibrecloud:instrumentation")
            .apply { setReferenceCounted(false); acquire() }
        super.onCreate(arguments)
    }

    override fun finish(resultCode: Int, results: Bundle?) {
        wakeLock?.release()
        super.finish(resultCode, results)
    }
}
