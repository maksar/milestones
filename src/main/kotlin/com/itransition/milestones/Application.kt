package com.itransition.milestones



import com.atlassian.jira.rest.client.api.domain.IssueFieldId
import com.atlassian.jira.rest.client.api.domain.IssueFieldId.ISSUE_TYPE_FIELD
import com.atlassian.jira.rest.client.api.domain.IssueFieldId.PROJECT_FIELD
import com.atlassian.jira.rest.client.api.domain.IssueFieldId.SUMMARY_FIELD
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
import kotlinx.serialization.Serializable
import org.codehaus.jettison.json.JSONObject
import org.joda.time.DateTime
import java.util.*
import kotlin.math.pow
import kotlin.math.roundToInt
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.server.cio.CIO as ServerCIO

@Serializable
data class Card(val summary: String, val department: String)

@Serializable
data class Summary(val key: String)

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

fun team(c: String) = "Team $c"

val teamHeads = mapOf(
    team("Salesforce") to "s.korolev",
    team("Brest") to "v.ermolik",
    team("Poland") to "v.krukovsky",
    team("Suboch") to "e.suboch",
    team("Strelchenko") to "k.strelchenko",
    team("Shults") to "p.shults",
    team("Romanchenko") to "a.romanchenko",
    team("Nikitin") to "e.nikitin",
    team("Mazur-Grabovsky") to "d.mazur-grabovsky",
    team("Mahnach") to "v.mahnach",
    team("Korzun") to "a.korzun",
    team("Korolev") to "s.korolev",
    team("Karpenkov") to "d.karpenkov",
    team("Kalganov") to "a.kalganov",
    team("Gomanchuk") to "s.gomanchuk",
    team("Chakur") to "s.chakur",
    team("Botko") to "a.botko",
    team("Bogomazov") to "a.bogomazov",
    team("Atroshko") to "g.atroshko",
    team("Adamova") to "n.adamova",
    team("Shestakov") to "a.shestakov"
)

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
            install(ContentNegotiation) { json() }
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
                                projectCards(setOf(env[fields.first], env[fields.second]))
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
                            projectCards(setOf(env[MILESTONES_JIRA_CUSTOMER_REGION_FIELD]))
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
                            FieldInput(SUMMARY_FIELD, card.summary)
                        )).get()
                        jiraClient.issueClient.updateIssue(issue.key, createWithFields(
                            FieldInput(env[MILESTONES_JIRA_TEAM_HEAD_FIELD], ComplexIssueInputFieldValue(mapOf("name" to teamHeads.getValue(card.department)))),
                            FieldInput(env[MILESTONES_JIRA_DEPARTMENT_FIELD], ComplexIssueInputFieldValue(mapOf("value" to "Production")))
                        )).get()
                        call.respondText(issue.key)
                    }

                    post("/") {
                        log.process("Fetching Project Cards") {
                            projectCards(setOf(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD], env[MILESTONES_JIRA_PREVIOUS_MONTH_EFFORTS_FIELD], env[MILESTONES_JIRA_FIRST_UOW_FIELD], env[MILESTONES_JIRA_TYPE_OF_CONTRACT_FIELD])).associateBy { it.key }
                        }.let { mapping ->
                            log.process("Executing callback") {
                                HttpClient(ClientCIO) { install(JsonFeature) }.post<Any>(env[MILESTONES_CALLBACK_URL]) {
                                    contentType(Json)
                                    body = call.receive<Array<Summary>>()
                                        .also {  log.trace("Got: ${it.map(Summary::key).joinToString(", ")} projects in request") }
                                        .sortedBy { it.key }.filter { it.key.isNotEmpty() }
                                        .mapNotNull { project ->
                                            mapping[project.key]?.let { card ->
                                                val totalEfforts = ((card.getField(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD])?.value ?: 0.0) as Double / 168).roundTo(2)
                                                val lastMonthEfforts = ((card.getField(env[MILESTONES_JIRA_PREVIOUS_MONTH_EFFORTS_FIELD])?.value ?: 0.0) as Double / 168).roundTo(2)
                                                val typeOfContract = (card.getField(env[MILESTONES_JIRA_TYPE_OF_CONTRACT_FIELD])?.value?.let {
                                                    (it as JSONObject).getString("value")
                                                } ?: "")
                                                val date = card.getField(env[MILESTONES_JIRA_FIRST_UOW_FIELD])?.value?.let { DateTime.parse(it.toString()).toString("dd.MM.yyyy") } ?: ""
                                                Effort(project.key, card.status.name, card.summary, totalEfforts, lastMonthEfforts, typeOfContract, date)
                                            }
                                        }.also { log.trace("Going to send: ${it.map(Effort::summary).joinToString(", ")} to callback") }
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