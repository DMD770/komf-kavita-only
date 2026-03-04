package snd.komf.mediaserver.kavita

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

class KavitaScanState {
    private val scanInProgress = AtomicBoolean(false)

    fun isScanInProgress(): Boolean = scanInProgress.get()

    fun markScanStarted() {
        val wasSet = scanInProgress.getAndSet(true)
        if (!wasSet) {
            logger.warn { "Detected active Kavita scan (ScanProgress started)." }
        }
    }

    fun markScanEnded() {
        val wasSet = scanInProgress.getAndSet(false)
        if (wasSet) {
            logger.info { "Detected Kavita scan completion (ScanProgress ended)." }
        }
    }

    suspend fun awaitIdle(
        timeoutMs: Long,
        pollIntervalMs: Long,
        onWaiting: (elapsedMs: Long, timeoutMs: Long) -> Unit = { _, _ -> }
    ): Boolean {
        if (!isScanInProgress()) return true

        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L)
        val safePollMs = pollIntervalMs.coerceAtLeast(250L)
        val startedMs = System.currentTimeMillis()
        var lastLogMs = 0L

        while (isScanInProgress()) {
            val elapsedMs = System.currentTimeMillis() - startedMs
            if (elapsedMs >= safeTimeoutMs) return false

            if (elapsedMs - lastLogMs >= 15_000L) {
                onWaiting(elapsedMs, safeTimeoutMs)
                lastLogMs = elapsedMs
            }
            delay(safePollMs)
        }
        return true
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

