package snd.komf.mediaserver.kavita

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KavitaWriteScanGateTest {

    @Test
    fun `write gate pauses until scan ends and quiet period elapses`() = runBlocking {
        val scanState = KavitaScanState().also { it.markScanStarted() }
        val gate = KavitaWriteScanGate(
            scanState = scanState,
            pausePolicy = KavitaScanPausePolicy(
                pauseOnActiveScan = true,
                resumeQuietPeriodMs = 400,
                pauseTimeoutMs = 10_000,
                pollIntervalMs = 250
            )
        )

        launch {
            delay(350)
            scanState.markScanEnded()
        }

        val elapsedMs = measureTimeMillis {
            gate.awaitSafeWriteWindow(KavitaEndpoint.UPLOAD_VOLUME)
        }

        assertTrue(elapsedMs >= 700, "expected gate to wait for scan end + quiet period, got ${elapsedMs}ms")
    }

    @Test
    fun `write gate throws timeout when scan remains active`() {
        runBlocking {
            val scanState = KavitaScanState().also { it.markScanStarted() }
            val gate = KavitaWriteScanGate(
                scanState = scanState,
                pausePolicy = KavitaScanPausePolicy(
                    pauseOnActiveScan = true,
                    resumeQuietPeriodMs = 0,
                    pauseTimeoutMs = 600,
                    pollIntervalMs = 250
                )
            )

            assertFailsWith<KavitaActiveScanPauseTimeoutException> {
                gate.awaitSafeWriteWindow(KavitaEndpoint.SERIES_METADATA_POST)
            }
        }
    }
}
