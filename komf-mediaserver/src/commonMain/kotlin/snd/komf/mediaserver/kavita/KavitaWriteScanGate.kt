package snd.komf.mediaserver.kavita

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay

class KavitaWriteScanGate(
    private val scanState: KavitaScanState?,
    private val pausePolicy: KavitaScanPausePolicy,
) {
    suspend fun awaitSafeWriteWindow(endpoint: KavitaEndpoint) {
        if (!pausePolicy.pauseOnActiveScan) return
        val state = scanState ?: return

        val timeoutMs = pausePolicy.pauseTimeoutMs.coerceAtLeast(0L)
        val pollMs = pausePolicy.pollIntervalMs.coerceAtLeast(250L)
        val quietMs = pausePolicy.resumeQuietPeriodMs.coerceAtLeast(0L)
        val startedAt = System.currentTimeMillis()
        var waitingLogged = false
        var lastProgressLogAt = startedAt

        while (true) {
            val now = System.currentTimeMillis()
            val elapsedMs = now - startedAt
            if (timeoutMs > 0 && elapsedMs >= timeoutMs) {
                val waitingOn = state.activeActivities().joinToString(", ").ifBlank { "unknown" }
                val message = "Kavita scan safety timeout before write endpoint=${endpoint.key}; " +
                    "still waiting on activities=[$waitingOn], elapsedMs=$elapsedMs, timeoutMs=$timeoutMs"
                logger.error { message }
                throw KavitaActiveScanPauseTimeoutException(message)
            }

            if (!state.isBusy()) {
                if (!waitingLogged) return
                if (quietMs <= 0L) {
                    logger.info {
                        "Kavita write gate resumed endpoint=${endpoint.key} without quiet period; totalWaitMs=$elapsedMs"
                    }
                    return
                }

                val quietStart = System.currentTimeMillis()
                logger.info {
                    "Kavita write gate entering quiet period before endpoint=${endpoint.key}; " +
                        "quietMs=$quietMs totalWaitMs=$elapsedMs"
                }
                var quietInterrupted = false
                while (System.currentTimeMillis() - quietStart < quietMs) {
                    if (state.isBusy()) {
                        quietInterrupted = true
                        val active = state.activeActivities().joinToString(", ").ifBlank { "unknown" }
                        logger.warn {
                            "Kavita write gate quiet period interrupted before endpoint=${endpoint.key}; " +
                                "active=[$active]"
                        }
                        break
                    }
                    delay(pollMs)
                }

                if (!quietInterrupted) {
                    val totalWaitMs = System.currentTimeMillis() - startedAt
                    logger.info {
                        "Kavita write gate resumed endpoint=${endpoint.key} after quiet period; totalWaitMs=$totalWaitMs"
                    }
                    return
                }
            } else {
                val active = state.activeActivities().joinToString(", ").ifBlank { "unknown" }
                if (!waitingLogged) {
                    waitingLogged = true
                    logger.warn {
                        "Kavita write gate pausing endpoint=${endpoint.key}; activeActivities=[$active], " +
                            "pollMs=$pollMs timeoutMs=$timeoutMs quietMs=$quietMs"
                    }
                } else if (now - lastProgressLogAt >= 15_000L) {
                    logger.warn {
                        "Kavita write gate still paused endpoint=${endpoint.key}; activeActivities=[$active], " +
                            "elapsedMs=$elapsedMs timeoutMs=$timeoutMs"
                    }
                    lastProgressLogAt = now
                }
            }

            delay(pollMs)
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}

class KavitaActiveScanPauseTimeoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class KavitaScanPausePolicy(
    val pauseOnActiveScan: Boolean = true,
    val resumeQuietPeriodMs: Long = 30_000L,
    val pauseTimeoutMs: Long = 1_800_000L,
    val pollIntervalMs: Long = 2_000L
)
