import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val logback_version: String by project
val ktor_version: String by project
val kotlin_version: String by project

plugins {
    application
    kotlin("jvm") version "1.4.30"
    kotlin("plugin.serialization") version "1.4.30"
}

group = "com.itransition.milestones"
version = "1.0"

application {
    mainClassName = "com.itransition.milestones.ApplicationKt"
}

repositories {
    jcenter()
    maven("https://kotlin.bintray.com/ktor")
    maven("https://packages.atlassian.com/maven/repository/public")
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-cio:$ktor_version")
    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-auth:$ktor_version")
    implementation("io.ktor:ktor-jackson:$ktor_version")
    implementation("io.ktor:ktor-client-core:$ktor_version")
    implementation("io.ktor:ktor-client-cio:$ktor_version")
    implementation("io.ktor:ktor-client-jackson:$ktor_version")
    implementation("io.ktor:ktor-serialization:$ktor_version")

    implementation("com.atlassian.jira", "jira-rest-java-client-app", "5.2.2") {
        exclude("org.slf4j", "slf4j-log4j12")
    }
    implementation("com.natpryce", "konfig", "1.6.10.0")
    implementation("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.4.2")
    implementation("org.jetbrains.kotlinx:atomicfu:0.15.1")
}
