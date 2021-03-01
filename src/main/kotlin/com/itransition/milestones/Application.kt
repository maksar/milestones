package com.itransition.milestones

import io.atlassian.fugue.Iterables.rangeUntil
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.jackson.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toCollection
import kotlin.math.pow
import kotlin.math.roundToInt
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO


data class Summary(val summary: String)
data class Effort(val summary: String, val efforts: Double)

fun Double.roundTo(numFractionDigits: Int): Double =
    10.0.pow(numFractionDigits.toDouble()).let { factor ->
        (this * factor).roundToInt() / factor
    }

@FlowPreview
fun main() {
    configureLogs()
    embeddedServer(ServerCIO, environment = applicationEngineEnvironment {
        connector { port = env[MILESTONES_PORT] }
        module {
            install(CallLogging) { }
            install(Authentication) { basic { validate { if (it.name == env[MILESTONES_USERNAME] && it.password == env[MILESTONES_PASSWORD]) UserIdPrincipal(it.name) else null } } }
            install(ContentNegotiation) { jackson() }

            routing {
                authenticate {
                    post("/") {
                        log.process("Fetching Project Cards") {
                            search(0, 1).total.let { total ->
                                rangeUntil(0, total, env[MILESTONES_PAGE_SIZE]).asFlow()
                                    .concurrentFlatMap { start -> search(start, env[MILESTONES_PAGE_SIZE]).issues }
                                    .toCollection(mutableListOf())
                            }
                        }.associateBy { it.summary }.let { mapping ->
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