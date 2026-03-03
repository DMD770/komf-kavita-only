package snd.komf.app.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import snd.komf.mediaserver.kavita.KavitaApiCompatibilityChecker

class KavitaHealthRoutes(
    private val compatibilityChecker: Flow<KavitaApiCompatibilityChecker>,
) {

    fun registerRoutes(routing: Route) {
        routing.route("/health") {
            get("/compat") {
                val report = compatibilityChecker.first().runChecksAndLog()
                val status = when (report.overallStatus) {
                    "PASS", "WARN" -> HttpStatusCode.OK
                    else -> HttpStatusCode.ServiceUnavailable
                }
                call.respond(status, report)
            }
        }
    }
}
