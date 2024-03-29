package com.itransition.milestones

import com.natpryce.konfig.*
import java.io.File

val MILESTONES_JIRA_URL by stringType
val MILESTONES_JIRA_USERNAME by stringType
val MILESTONES_JIRA_PASSWORD by stringType
val MILESTONES_JIRA_PROJECT by stringType
val MILESTONES_JIRA_DEPARTMENT_FIELD by stringType
val MILESTONES_JIRA_CURRENT_STATUS_FIELD by stringType
val MILESTONES_JIRA_TEAM_HEAD_FIELD by stringType
val MILESTONES_JIRA_CTO_REPRESENTATIVE_FIELD by stringType
val MILESTONES_JIRA_TOTAL_EFFORTS_FIELD by stringType
val MILESTONES_JIRA_PREVIOUS_MONTH_EFFORTS_FIELD by stringType
val MILESTONES_JIRA_FIRST_UOW_FIELD by stringType
val MILESTONES_JIRA_CUSTOMER_REGION_FIELD by stringType
val MILESTONES_JIRA_INDUSTRY_FIELD by stringType
val MILESTONES_JIRA_INDUSTRY_CLARIFICATION_FIELD by stringType
val MILESTONES_JIRA_SOLUTION_TYPE_FIELD by stringType
val MILESTONES_JIRA_SOLUTION_TYPE_CLARIFICATION_FIELD by stringType
val MILESTONES_JIRA_TYPE_OF_SERVICE_FIELD by stringType
val MILESTONES_JIRA_TYPE_OF_SERVICE_CLARIFICATION_FIELD by stringType
val MILESTONES_JIRA_TYPE_OF_CONTRACT_FIELD by stringType
val MILESTONES_JIRA_TYPE_OF_CONTRACT_CLARIFICATION_FIELD by stringType
val MILESTONES_PORT by intType
val MILESTONES_USERNAME by stringType
val MILESTONES_PASSWORD by stringType
val MILESTONES_PAGE_SIZE by intType
val MILESTONES_DEPARTMENTS_MAPPING by listType(listType(stringType, ":".toRegex()).wrappedAs { Pair("Team ${it.first()}", it.last()) }, ",".toRegex()).wrappedAs { it.toMap() }
val env = EnvironmentVariables() overriding ConfigurationProperties.fromOptionalFile(File(".env"))
