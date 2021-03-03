package com.itransition.milestones

import com.natpryce.konfig.ConfigurationProperties
import com.natpryce.konfig.EnvironmentVariables
import com.natpryce.konfig.getValue
import com.natpryce.konfig.intType
import com.natpryce.konfig.overriding
import com.natpryce.konfig.stringType
import java.io.File

val MILESTONES_JIRA_URL by stringType
val MILESTONES_JIRA_USERNAME by stringType
val MILESTONES_JIRA_PASSWORD by stringType
val MILESTONES_JIRA_PROJECT by stringType
val MILESTONES_JIRA_TOTAL_EFFORTS_FIELD by stringType
val MILESTONES_JIRA_CUSTOMER_REGION_FIELD by stringType
val MILESTONES_PORT by intType
val MILESTONES_USERNAME by stringType
val MILESTONES_PASSWORD by stringType
val MILESTONES_CALLBACK_URL by stringType
val MILESTONES_PAGE_SIZE by intType
val env = EnvironmentVariables() overriding ConfigurationProperties.fromOptionalFile(File(".env"))
