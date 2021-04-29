package com.itransition.milestones

import com.atlassian.event.api.EventPublisher
import com.atlassian.httpclient.apache.httpcomponents.DefaultHttpClientFactory
import com.atlassian.httpclient.api.factory.HttpClientOptions
import com.atlassian.jira.rest.client.api.AuthenticationHandler
import com.atlassian.jira.rest.client.api.IssueRestClient
import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.api.MetadataRestClient
import com.atlassian.jira.rest.client.api.SearchRestClient
import com.atlassian.jira.rest.client.api.SessionRestClient
import com.atlassian.jira.rest.client.api.domain.CimFieldInfo
import com.atlassian.jira.rest.client.api.domain.CustomFieldOption
import com.atlassian.jira.rest.client.api.domain.IssueFieldId
import com.atlassian.jira.rest.client.api.domain.Project
import com.atlassian.jira.rest.client.api.domain.SearchResult
import com.atlassian.jira.rest.client.internal.async.AsynchronousHttpClientFactory
import com.atlassian.jira.rest.client.internal.async.AsynchronousIssueRestClient
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClient
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory
import com.atlassian.jira.rest.client.internal.async.AsynchronousMetadataRestClient
import com.atlassian.jira.rest.client.internal.async.AsynchronousSearchRestClient
import com.atlassian.jira.rest.client.internal.async.AsynchronousSessionRestClient
import com.atlassian.jira.rest.client.internal.async.AtlassianHttpClientDecorator
import com.atlassian.jira.rest.client.internal.async.DisposableHttpClient
import com.atlassian.jira.rest.client.internal.json.CimFieldsInfoMapJsonParser
import com.atlassian.jira.rest.client.internal.json.JsonObjectParser
import com.atlassian.sal.api.ApplicationProperties
import com.atlassian.sal.api.UrlMode
import com.atlassian.sal.api.executor.ThreadLocalContextManager
import io.atlassian.fugue.Iterables.rangeUntil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.toList
import org.codehaus.jettison.json.JSONException
import org.codehaus.jettison.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.util.*
import javax.ws.rs.core.UriBuilder

class AsynchronousHttpClientFactoryCustom : AsynchronousHttpClientFactory() {
    private class NoOpEventPublisher : EventPublisher {
        override fun publish(o: Any) {}
        override fun register(o: Any) {}
        override fun unregister(o: Any) {}
        override fun unregisterAll() {}
    }

    private class RestClientApplicationProperties constructor(jiraURI: URI) : ApplicationProperties {
        private val baseUrl: String = jiraURI.path
        override fun getBaseUrl(): String = baseUrl
        override fun getBaseUrl(urlMode: UrlMode): String = baseUrl
        override fun getDisplayName(): String = "Atlassian JIRA Rest Java Client"
        override fun getPlatformId(): String = ApplicationProperties.PLATFORM_JIRA
        override fun getVersion(): String = "unknown"
        override fun getBuildDate(): Date = throw UnsupportedOperationException()
        override fun getBuildNumber(): String = 0.toString()
        override fun getHomeDirectory(): File = File(".")
        override fun getPropertyValue(s: String): String = throw UnsupportedOperationException()
    }

    private class NoopThreadLocalContextManager : ThreadLocalContextManager<Any?> {
        override fun getThreadLocalContext(): Any? = null
        override fun setThreadLocalContext(context: Any?) {}
        override fun clearThreadLocalContext() {}
    }

    private class AtlassianHttpClientDecoratorCustom(
        val defaultHttpClientFactory: DefaultHttpClientFactory<Any?>,
        val httpClient: com.atlassian.httpclient.api.HttpClient,
        authenticationHandler: AuthenticationHandler
    ) : AtlassianHttpClientDecorator(httpClient, authenticationHandler) {
        override fun destroy() {
            defaultHttpClientFactory.dispose(httpClient)
        }
    }

    override fun createClient(serverUri: URI, authenticationHandler: AuthenticationHandler): DisposableHttpClient =
        DefaultHttpClientFactory(
            NoOpEventPublisher(),
            RestClientApplicationProperties(serverUri),
            NoopThreadLocalContextManager()
        ).let { defaultHttpClientFactory ->
            AtlassianHttpClientDecoratorCustom(
                defaultHttpClientFactory,
                defaultHttpClientFactory.create(HttpClientOptions().apply { ignoreCookies = true }),
                authenticationHandler
            )
        }
}

class EditIssueMetadataJsonParser : JsonObjectParser<Map<String, CimFieldInfo>> {
    private val projectsParser = CimFieldsInfoMapJsonParser()

    @Throws(JSONException::class)
    override fun parse(json: JSONObject): Map<String, CimFieldInfo> {
        return projectsParser.parse(json.getJSONObject("fields"))
    }
}

class MyAsynchronousIssueRestClient(
    private val baseUri: URI,
    client: com.atlassian.httpclient.api.HttpClient,
    sessionRestClient: SessionRestClient,
    metadataRestClient: MetadataRestClient,
    private val searchRestClient: SearchRestClient
) : AsynchronousIssueRestClient(baseUri, client, sessionRestClient, metadataRestClient) {

    fun getAllowedValues(project: Project): Map<String, List<String>> {
        val issue = searchRestClient.searchJql("project = ${project.key}", 1, 0, setOf()).get().issues.first()
        val uriBuilder = UriBuilder.fromUri(baseUri).path("issue/${issue.id}/editmeta")
        val fields = getAndParse(uriBuilder.build(), EditIssueMetadataJsonParser()).get()
        return fields.filterValues { it.allowedValues != null }.map { it.value.id!! to (it.value.allowedValues as Iterable<CustomFieldOption>).map { it.value } }.toMap()
    }
}

class MyAsynchronousJiraRestClient(serverUri: URI, httpClient: DisposableHttpClient) :
    AsynchronousJiraRestClient(serverUri, httpClient) {
    private val baseUri: URI = UriBuilder.fromUri(serverUri).path("/rest/api/latest").build()
    private val metadataRestClient = AsynchronousMetadataRestClient(baseUri, httpClient)
    private val sessionRestClient = AsynchronousSessionRestClient(serverUri, httpClient)
    private val searchRestClient = AsynchronousSearchRestClient(baseUri, httpClient)

    private val issueRestClient = MyAsynchronousIssueRestClient(baseUri, httpClient, sessionRestClient, metadataRestClient, searchRestClient)
    override fun getIssueClient(): IssueRestClient {
        return issueRestClient
    }
}


class AsynchronousJiraRestClientFactoryCustom : AsynchronousJiraRestClientFactory() {
    override fun create(serverUri: URI, authenticationHandler: AuthenticationHandler): JiraRestClient =
        MyAsynchronousJiraRestClient(
            serverUri,
            AsynchronousHttpClientFactoryCustom().createClient(serverUri, authenticationHandler)
        )
}

internal val jiraClient = AsynchronousJiraRestClientFactoryCustom().createWithBasicHttpAuthentication(
    URI(env[MILESTONES_JIRA_URL]), env[MILESTONES_JIRA_USERNAME], env[MILESTONES_JIRA_PASSWORD]
)

val MINIMUM_SET_OF_FIELDS = setOf(
        IssueFieldId.SUMMARY_FIELD,
        IssueFieldId.ISSUE_TYPE_FIELD,
        IssueFieldId.CREATED_FIELD,
        IssueFieldId.UPDATED_FIELD,
        IssueFieldId.PROJECT_FIELD,
        IssueFieldId.STATUS_FIELD,
    ).map(IssueFieldId::id).toSet().plus(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD])

fun <T> Flow<T>.retryOnTimeouts() =
    this.flowOn(Dispatchers.IO)
        .retry { cause -> generateSequence(cause, Throwable::cause).any { it is SocketTimeoutException } }

private fun search(jql: String, start: Int, per: Int, fields: Set<String> = setOf()): SearchResult =
    jiraClient.searchClient.searchJql(jql, per, start, MINIMUM_SET_OF_FIELDS.plus(fields)).get()

@FlowPreview
fun <T, R> Flow<T>.concurrentFlatMap(transform: suspend (T) -> Iterable<R>) =
    flatMapMerge { value ->
        flow { emitAll(transform(value).asFlow()) }
    }.retryOnTimeouts()

@FlowPreview
suspend fun projectCards(jql: String, fields: Set<String>) =
    search(jql, 0, 1).total.let { total ->
        rangeUntil(0, total, env[MILESTONES_PAGE_SIZE]).asFlow()
            .concurrentFlatMap { start -> search(jql, start, env[MILESTONES_PAGE_SIZE], fields).issues }
            .toList()
    }

@FlowPreview
suspend fun allProjectCards(fields: Set<String>) =
    projectCards("project = ${env[MILESTONES_JIRA_PROJECT]}", fields)
