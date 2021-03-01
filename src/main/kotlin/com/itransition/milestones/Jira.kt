package com.itransition.milestones

import com.atlassian.event.api.EventPublisher
import com.atlassian.httpclient.apache.httpcomponents.DefaultHttpClientFactory
import com.atlassian.httpclient.api.factory.HttpClientOptions
import com.atlassian.jira.rest.client.api.AuthenticationHandler
import com.atlassian.jira.rest.client.api.JiraRestClient
import com.atlassian.jira.rest.client.api.domain.IssueFieldId
import com.atlassian.jira.rest.client.api.domain.SearchResult
import com.atlassian.jira.rest.client.internal.async.AsynchronousHttpClientFactory
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClient
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory
import com.atlassian.jira.rest.client.internal.async.AtlassianHttpClientDecorator
import com.atlassian.jira.rest.client.internal.async.DisposableHttpClient
import com.atlassian.sal.api.ApplicationProperties
import com.atlassian.sal.api.UrlMode
import com.atlassian.sal.api.executor.ThreadLocalContextManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retry
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.util.*

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
        override fun getHomeDirectory(): File? = File(".")
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

class AsynchronousJiraRestClientFactoryCustom : AsynchronousJiraRestClientFactory() {
    override fun create(serverUri: URI, authenticationHandler: AuthenticationHandler): JiraRestClient =
        AsynchronousJiraRestClient(
            serverUri,
            AsynchronousHttpClientFactoryCustom().createClient(serverUri, authenticationHandler)
        )
}

internal val jiraClient = AsynchronousJiraRestClientFactoryCustom().createWithBasicHttpAuthentication(
    URI(env[MILESTONES_JIRA_URL]), env[MILESTONES_JIRA_USERNAME], env[MILESTONES_JIRA_PASSWORD]
)

val MINIMUM_SET_OF_FIELDS = setOf(
    arrayOf(
        IssueFieldId.SUMMARY_FIELD,
        IssueFieldId.ISSUE_TYPE_FIELD,
        IssueFieldId.CREATED_FIELD,
        IssueFieldId.UPDATED_FIELD,
        IssueFieldId.PROJECT_FIELD,
        IssueFieldId.STATUS_FIELD,
    ).map(IssueFieldId::id).plus(env[MILESTONES_JIRA_TOTAL_EFFORTS_FIELD]).joinToString(separator = ",")
)

fun <T> Flow<T>.retryOnTimeouts() =
    this.flowOn(Dispatchers.IO)
        .retry { cause -> generateSequence(cause, Throwable::cause).any { it is SocketTimeoutException } }

@FlowPreview
fun <T, R> Flow<T>.concurrentFlatMap(transform: suspend (T) -> Iterable<R>) = flatMapMerge { value ->
    flow { emitAll(transform(value).asFlow()) }
}.retryOnTimeouts()

fun search(start: Int, per: Int): SearchResult = jiraClient.searchClient.searchJql("project = ${env[MILESTONES_JIRA_PROJECT]}", per, start, MINIMUM_SET_OF_FIELDS).get()
