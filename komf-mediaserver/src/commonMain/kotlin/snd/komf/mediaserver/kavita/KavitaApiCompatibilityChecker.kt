package snd.komf.mediaserver.kavita

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import snd.komf.mediaserver.kavita.model.KavitaChapterId
import snd.komf.mediaserver.kavita.model.KavitaLibrary
import snd.komf.mediaserver.kavita.model.KavitaLibraryId
import snd.komf.mediaserver.kavita.model.KavitaSeriesId

@Serializable
data class KavitaApiCheckResult(
    val name: String,
    val status: String,
    val detail: String? = null
)

@Serializable
data class KavitaApiCompatibilityReport(
    val checkedAt: String,
    val overallStatus: String,
    val checks: List<KavitaApiCheckResult>
)

class KavitaApiCompatibilityChecker(
    private val kavitaClient: KavitaClient
) {
    suspend fun runChecks(): KavitaApiCompatibilityReport {
        val checks = mutableListOf<KavitaApiCheckResult>()

        var libraries: Collection<KavitaLibrary>? = null
        val librariesResult = runCheck("GET api/library/libraries") {
            libraries = kavitaClient.getLibraries()
            CheckOutcome.Pass("reachable (${libraries?.size ?: 0} libraries)")
        }
        checks += librariesResult.result

        val firstLibraryId = libraries?.firstOrNull()?.id

        if (firstLibraryId != null) {
            checks += runCheck("POST api/series/v2 (library filter)") {
                kavitaClient.getSeries(firstLibraryId, page = 0)
                CheckOutcome.Pass("reachable")
            }.result
        } else {
            checks += KavitaApiCheckResult(
                name = "POST api/series/v2 (library filter)",
                status = "SKIP",
                detail = "No libraries available to run non-destructive probe"
            )
        }

        checks += runCheck("POST api/series/scan (payload contract)") {
            val status = runExpectedClientError {
                kavitaClient.scanSeries(KavitaLibraryId(0), KavitaSeriesId(0))
            }
            when (status) {
                null -> CheckOutcome.Pass("request accepted")
                HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed ->
                    CheckOutcome.Fail("endpoint missing or method changed (${status.value})")

                else -> CheckOutcome.Pass("endpoint reachable (${status.value})")
            }
        }.result

        checks += runCheck("POST api/upload/chapter|api/upload/reset-chapter-lock (cover reset)") {
            val status = runExpectedClientError {
                kavitaClient.resetChapterLock(KavitaChapterId(0))
            }
            when (status) {
                null -> CheckOutcome.Pass("request accepted")
                HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed ->
                    CheckOutcome.Fail("endpoint missing or method changed (${status.value})")

                else -> CheckOutcome.Pass("endpoint reachable (${status.value})")
            }
        }.result

        val overall = when {
            checks.any { it.status == "FAIL" } -> "FAIL"
            checks.any { it.status == "WARN" } -> "WARN"
            else -> "PASS"
        }

        return KavitaApiCompatibilityReport(
            checkedAt = kotlin.time.Clock.System.now().toString(),
            overallStatus = overall,
            checks = checks
        )
    }

    suspend fun runChecksAndLog(): KavitaApiCompatibilityReport {
        val report = runChecks()
        when (report.overallStatus) {
            "PASS" -> logger.info { "Kavita API compatibility check passed (${report.checks.size} checks)" }
            "WARN" -> logger.warn { "Kavita API compatibility check has warnings" }
            else -> logger.error { "Kavita API compatibility check failed" }
        }
        report.checks.forEach { check ->
            val message = "${check.status} ${check.name}${check.detail?.let { ": $it" } ?: ""}"
            when (check.status) {
                "PASS" -> logger.info { message }
                "WARN" -> logger.warn { message }
                "SKIP" -> logger.info { message }
                else -> logger.error { message }
            }
        }
        return report
    }

    private suspend fun runExpectedClientError(block: suspend () -> Unit): HttpStatusCode? {
        return try {
            block()
            null
        } catch (e: ResponseException) {
            e.response.status
        }
    }

    private suspend fun runCheck(name: String, block: suspend () -> CheckOutcome): CheckRunResult {
        return try {
            val outcome = block()
            CheckRunResult(outcome = outcome, result = outcome.toResult(name))
        } catch (e: ResponseException) {
            val detail = "HTTP ${e.response.status.value}"
            val outcome = CheckOutcome.Fail(detail)
            CheckRunResult(outcome = outcome, result = outcome.toResult(name))
        } catch (e: Exception) {
            val outcome = CheckOutcome.Fail(e.message ?: e::class.simpleName ?: "unknown error")
            CheckRunResult(outcome = outcome, result = outcome.toResult(name))
        }
    }

    private data class CheckRunResult(
        val outcome: CheckOutcome,
        val result: KavitaApiCheckResult
    )

    private sealed interface CheckOutcome {
        data class Pass(val detail: String? = null) : CheckOutcome
        data class Warn(val detail: String? = null) : CheckOutcome
        data class Fail(val detail: String? = null) : CheckOutcome
    }

    private fun CheckOutcome.toResult(name: String): KavitaApiCheckResult = when (this) {
        is CheckOutcome.Pass -> KavitaApiCheckResult(name, "PASS", detail)
        is CheckOutcome.Warn -> KavitaApiCheckResult(name, "WARN", detail)
        is CheckOutcome.Fail -> KavitaApiCheckResult(name, "FAIL", detail)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
