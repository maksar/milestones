package com.itransition.milestones



import com.atlassian.jira.rest.client.api.domain.IssueFieldId.*
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue
import com.atlassian.jira.rest.client.api.domain.input.FieldInput
import com.atlassian.jira.rest.client.api.domain.input.IssueInput.createWithFields
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.client.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpStatusCode.Companion.OK
import io.ktor.network.tls.certificates.*
import io.ktor.network.tls.extensions.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.serialization.*
import io.ktor.server.engine.*
import kotlinx.coroutines.FlowPreview
    import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.codehaus.jettison.json.JSONObject
import org.joda.time.DateTime
import java.util.*
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.system.exitProcess
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

@Serializable
data class Card(val summary: String, val description: String, val department: String, val ctoRepresentative: String, val currentStatus: String)

@Serializable
data class CardCreated(val projectCardId: String, val departmentHead: String)

@Serializable
sealed class PayloadNew {
    abstract val callback: String

    @Serializable
    @SerialName("keys")
    data class KeysPayload(override val callback: String, val keys: Array<String>) : PayloadNew()

    @Serializable
    @SerialName("jql")
    data class JqlPayload(override val callback: String, val jql: String) : PayloadNew()
}

@Serializable
data class Effort(val key: String, val status: String, val summary: String, val totalEfforts: Double, val lastMonthEfforts: Double, val typeOfContract: String, val date: String)

@Serializable
data class StatItem(val parts: List<String>, val count: Int)

@Serializable
data class Statistics(val width: Int, val items: List<StatItem>)

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
            install(Authentication) {
                basic {
                    validate {
                        if (it.name == env[MILESTONES_USERNAME] && it.password == env[MILESTONES_PASSWORD]) UserIdPrincipal(it.name) else null
                    }
                }
            }
            install(ContentNegotiation) {
                json(Json { useArrayPolymorphism = false })

            }
            install(CORS) {
                method(HttpMethod.Options)
                anyHost()
                allowHeaders { true }
            }

            routing {
                authenticate {
                    mapOf(
                        "/industries" to Pair(MILESTONES_JIRA_INDUSTRY_FIELD, MILESTONES_JIRA_INDUSTRY_CLARIFICATION_FIELD),
                        "/solutions" to Pair(MILESTONES_JIRA_SOLUTION_TYPE_FIELD, MILESTONES_JIRA_SOLUTION_TYPE_CLARIFICATION_FIELD),
                        "/services" to Pair(MILESTONES_JIRA_TYPE_OF_SERVICE_FIELD, MILESTONES_JIRA_TYPE_OF_SERVICE_CLARIFICATION_FIELD),
                        "/contracts" to Pair(MILESTONES_JIRA_TYPE_OF_CONTRACT_FIELD, MILESTONES_JIRA_TYPE_OF_CONTRACT_CLARIFICATION_FIELD),
                    ).map { (route, fields) ->
                        post(route) {
                            val possibleValues = (jiraClient.issueClient as MyAsynchronousIssueRestClient).getAllowedValues(jiraClient.projectClient.getProject(env[MILESTONES_JIRA_PROJECT]).get())
                            log.process("Fetching Project Cards") {
                                allProjectCards(setOf(env[fields.first], env[fields.second]))
                            }.let { cards ->
                                val mainField = cards.map {
                                    listOf(
                                        it.getField(env[fields.first])?.value?.let {
                                            (it as JSONObject).getString("value")
                                        } ?: ""
                                    )
                                }
                                val clarifications = cards.map {
                                    it.getField(env[fields.second])?.value?.toString()?.split(", ") ?: emptyList()
                                }
                                val result = mainField.zip(clarifications, List<String>::plus)
                                val map = result.groupingBy { it }.eachCount()
                                val size = result.maxOf { it.size }

                                call.respond(
                                    Statistics(size, (result.distinct().union(possibleValues.getValue(env[fields.first]).map(::listOf))).map { item ->
                                        StatItem(
                                            item.plus(generateSequence { "" }.take(10)).take(size),
                                            map.getOrDefault(item, 0)
                                        )
                                    })
                                )
                            }
                        }
                    }

                    post ("/regions") {
                        log.process("Fetching Project Cards") {
                            allProjectCards(setOf(env[MILESTONES_JIRA_CUSTOMER_REGION_FIELD]))
                        }.let { cards ->
                            val regions = cards.mapNotNull { it.getField(env[MILESTONES_JIRA_CUSTOMER_REGION_FIELD])?.value?.toString() }
                            val map = regions.groupingBy { it }.eachCount()
                            val size = regions.maxOf { it.split(", ").size }
                            call.respond(
                                Statistics(size, regions.distinct().map { region ->
                                    StatItem(
                                        region.split(", ").plus(generateSequence { "" }.take(10)).take(size),
                                        map.getValue(region)
                                    )
                                })
                            )
                        }
                    }

                    post("/card") {
                        val card = call.receive<Card>()
                        val issue = jiraClient.issueClient.createIssue(createWithFields(
                            FieldInput(ISSUE_TYPE_FIELD, ComplexIssueInputFieldValue(mapOf("name" to jiraClient.projectClient.getProject(env[MILESTONES_JIRA_PROJECT]).get().issueTypes.first().name))),
                            FieldInput(PROJECT_FIELD, ComplexIssueInputFieldValue(mapOf("key" to env[MILESTONES_JIRA_PROJECT]))),
                            FieldInput(SUMMARY_FIELD, card.summary),
                            FieldInput(DESCRIPTION_FIELD, card.description)
                        )).get()
                        jiraClient.issueClient.updateIssue(issue.key, createWithFields(
                            FieldInput(env[MILESTONES_JIRA_TEAM_HEAD_FIELD], ComplexIssueInputFieldValue(mapOf("name" to env[MILESTONES_DEPARTMENTS_MAPPING].getValue(card.department)))),
                            FieldInput(env[MILESTONES_JIRA_CTO_REPRESENTATIVE_FIELD], ComplexIssueInputFieldValue(mapOf("name" to card.ctoRepresentative))),
                            FieldInput(env[MILESTONES_JIRA_DEPARTMENT_FIELD], ComplexIssueInputFieldValue(mapOf("value" to "Production"))),
                            FieldInput(env[MILESTONES_JIRA_CURRENT_STATUS_FIELD], card.currentStatus)
                        )).get()

                        call.respond(CardCreated(issue.key, env[MILESTONES_DEPARTMENTS_MAPPING].getValue(card.department)))
                    }

                    post("/") {
                        val fields = setOf(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD], env[MILESTONES_JIRA_PREVIOUS_MONTH_EFFORTS_FIELD], env[MILESTONES_JIRA_FIRST_UOW_FIELD], env[MILESTONES_JIRA_TYPE_OF_CONTRACT_FIELD])
                        call.receive<PayloadNew>().let { payload ->
                            log.process("Fetching Project Cards") {
                                when (payload) {
                                    is PayloadNew.JqlPayload -> projectCards(payload.jql, fields)
                                    is PayloadNew.KeysPayload -> projectCards("key in (${payload.keys.filter(String::isNotEmpty).joinToString(", ")})", fields)
                                }.associateBy { it.key }
                            }.let { mapping ->
                                log.process("Executing callback") {
                                    HttpClient(ClientCIO) { install(JsonFeature) }.post<Any>(payload.callback) {
                                        contentType(Json)
                                        body = mapping.keys
                                            .also { log.trace("Got: ${it.joinToString(", ")} projects in request") }
                                            .mapNotNull { project ->
                                                mapping[project]?.let { card ->
                                                    val totalEfforts = ((card.getField(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD])?.value ?: 0.0) as Double / 168).roundTo(2)
                                                    val lastMonthEfforts = ((card.getField(env[MILESTONES_JIRA_PREVIOUS_MONTH_EFFORTS_FIELD])?.value ?: 0.0) as Double / 168).roundTo(2)
                                                    val typeOfContract = (card.getField(env[MILESTONES_JIRA_TYPE_OF_CONTRACT_FIELD])?.value?.let {
                                                        (it as JSONObject).getString("value")
                                                    } ?: "")
                                                    val date = card.getField(env[MILESTONES_JIRA_FIRST_UOW_FIELD])?.value?.let { DateTime.parse(it.toString()).toString("dd.MM.yyyy") } ?: ""
                                                    Effort(project, card.status.name, card.summary, totalEfforts, lastMonthEfforts, typeOfContract, date)
                                                }
                                            }.also { log.trace("Going to send: ${it.map(Effort::summary).joinToString(", ")} to callback") }
                                        }
                                    }
                                }
                            }
                        call.respond(OK)
                    }
                }
            }
        }
    }, configure =
    {
        connectionGroupSize = 1
        workerGroupSize = 1
    }).start(true)
}