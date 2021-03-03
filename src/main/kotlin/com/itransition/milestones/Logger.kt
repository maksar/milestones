package com.itransition.milestones

import ch.qos.logback.classic.Level.ERROR
import ch.qos.logback.classic.Level.DEBUG
import ch.qos.logback.classic.Level.TRACE
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import org.slf4j.LoggerFactory

fun configureLogs() =
    (LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger).apply {
        addAppender(consoleAppender())
    }.also {
        mapOf(
            Logger.ROOT_LOGGER_NAME to ERROR,
            "org.apache.http" to DEBUG,
            "Application" to TRACE
        ).map { (namespace, level) ->
            (LoggerFactory.getLogger(namespace) as Logger).level = level
        }
    }

suspend fun <T> org.slf4j.Logger.process(message: String, block: suspend (Unit) -> T): T =
    System.currentTimeMillis().let { start ->
        trace("-> Starting process: $message")
        block.invoke(Unit).also {
            trace("<- Finished process: $message - ${System.currentTimeMillis() - start}ms")
        }
    }

private fun consoleAppender() = ConsoleAppender<ILoggingEvent>().apply {
    (LoggerFactory.getILoggerFactory() as LoggerContext).let { loggerContext ->
        loggerContext.reset()
        encoder = PatternLayoutEncoder().apply {
            pattern = "%d{YYYY-MM-dd HH:mm:ss.SSS} [%thread] %-5level %-9logger - %msg%n"
            context = loggerContext
            start()
        }
        context = loggerContext
        start()
    }
}
