package snd.komf.mediaserver.kavita

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class KavitaScanState {
    private val scanInProgress = AtomicBoolean(false)
    private val activeMaintenance = ConcurrentHashMap.newKeySet<String>()

    fun isScanInProgress(): Boolean = scanInProgress.get()
    fun isBusy(): Boolean = isScanInProgress() || activeMaintenance.isNotEmpty()
    fun activeActivities(): Set<String> {
        val activities = mutableSetOf<String>()
        if (isScanInProgress()) activities.add("ScanProgress")
        activities.addAll(activeMaintenance)
        return activities
    }

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

    fun markMaintenanceStarted(activityName: String) {
        val added = activeMaintenance.add(activityName)
        if (added) {
            logger.warn { "Detected active Kavita maintenance task ($activityName started)." }
        }
    }

    fun markMaintenanceEnded(activityName: String) {
        val removed = activeMaintenance.remove(activityName)
        if (removed) {
            logger.info { "Detected Kavita maintenance task completion ($activityName ended)." }
        }
    }

    suspend fun awaitIdle(
        timeoutMs: Long,
        pollIntervalMs: Long,
        onWaiting: (elapsedMs: Long, timeoutMs: Long) -> Unit = { _, _ -> }
    ): Boolean {
        if (!isBusy()) return true

        val safeTimeoutMs = timeoutMs.coerceAtLeast(0L)
        val safePollMs = pollIntervalMs.coerceAtLeast(250L)
        val startedMs = System.currentTimeMillis()
        var lastLogMs = 0L

        while (isBusy()) {
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
