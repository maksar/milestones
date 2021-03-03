package com.itransition.milestones

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import kotlinx.coroutines.FlowPreview
import kotlinx.serialization.Serializable
import org.slf4j.event.Level
import kotlin.math.pow
import kotlin.math.roundToInt
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

@Serializable
data class Summary(val summary: String)
@Serializable
data class Effort(val summary: String, val efforts: Double)
@Serializable
data class Region(val parts: List<String>, val count: Int)
@Serializable
data class Statistics(val width: Int, val regions: List<Region>)

fun Double.roundTo(numFractionDigits: Int): Double =
    10.0.pow(numFractionDigits.toDouble()).let { factor ->
        (this * factor).roundToInt() / factor
    }

@FlowPreview
fun main() {
    configureLogs()
    embeddedServer(ServerCIO, configure =
    {
        connectionGroupSize = 1
        workerGroupSize = 1
    }, environment = applicationEngineEnvironment {
        connector { port = env[MILESTONES_PORT] }
        module {
            install(CallLogging) { level = Level.DEBUG}
            install(Authentication) { basic { validate { if (it.name == env[MILESTONES_USERNAME] && it.password == env[MILESTONES_PASSWORD]) UserIdPrincipal(it.name) else null } } }
            install(ContentNegotiation) { json() }
            install(CORS) {
                method(HttpMethod.Options)
                anyHost()
                allowHeaders { true }
            }

            routing {
                authenticate {
                    post("/regions") {
                        log.process("Fetching Project Cards") {
                            projectCards(setOf(env[MILESTONES_JIRA_CUSTOMER_REGION_FIELD]))
                        }.let { cards ->
                            val regions = cards.mapNotNull { it.getField(env[MILESTONES_JIRA_CUSTOMER_REGION_FIELD])?.value?.toString() }
                            val map = regions.groupingBy { it }.eachCount()
                            val size = regions.map { it.split(", ").size }.maxOrNull()!!
                            call.respond(
                                Statistics(
                                    size,
                                    regions.distinct().map { region ->
                                        Region(region.split(", ").plus(generateSequence { "" }.take(10)).take(size), map.getValue(region))
                                    }
                                )
                            )
                        }
                    }

                    post("/") {
                        log.process("Fetching Project Cards") {
                            projectCards(setOf(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD])).associateBy { it.summary }
                        }.let { mapping ->
                            log.process("Executing callback") {
                                HttpClient(ClientCIO) {
                                    install(JsonFeature) { serializer = JacksonSerializer() }
                                }.post<Any>(env[MILESTONES_CALLBACK_URL]) {
                                    contentType(Json)
                                    body = call.receive<Array<Summary>>()
                                        .also { log.trace("Got: ${it.map(Summary::summary).joinToString(", ")} projects in request") }
                                        .sortedBy { it.summary }.filter { it.summary.isNotEmpty() }
                                        .mapNotNull { project ->
                                            mapping[project.summary]?.getField(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD])?.value?.let { efforts ->
                                                Effort(project.summary, (efforts as Double / 168).roundTo(2))
                                            }
                                        }.also { log.trace("Going to send: ${it.joinToString(", ") { "${it.summary} - ${it.efforts}" }} to callback") }
                                }
                            }
                        }
                        call.respond(OK)
                    }
                }
            }
        }
    }).start(true)
}