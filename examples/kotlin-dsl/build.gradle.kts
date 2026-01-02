import io.github.danielliu1123.deployer.PublishingType

plugins {
    id("io.github.danielliu1123.deployer") version "+"
}

group = "com.example"
description = "An example for maven-deployer using Kotlin DSL"

dependencies {
    api("org.apache.commons:commons-lang3:+")
}

deploy {
    dirs = allprojects.map { it.layout.buildDirectory.dir("repo").get().asFile }.filter { it.exists() }
    username = System.getenv("MAVENCENTRAL_USERNAME")
    password = System.getenv("MAVENCENTRAL_PASSWORD")
    publishingType = PublishingType.WAIT_FOR_PUBLISHED
}

apply(from = "${rootDir}/gradle/deploy.gradle")
