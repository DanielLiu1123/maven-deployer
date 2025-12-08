# Maven Deployer

A Gradle plugin that provides a unified (single) way to upload artifacts to Maven Central.

## Quick Start

Apply the plugin to your _root_ `build.gradle`:

```groovy
plugins {
    id "io.github.danielliu1123.deployer" version "<latest>"
}

deploy {
    // dirs to sign and upload 
    dirs.set(provider {
        subprojects.collect { e -> e.layout.buildDirectory.dir("repo").get().getAsFile() }
    })
    username = System.getenv("MAVENCENTRAL_USERNAME")
    password = System.getenv("MAVENCENTRAL_PASSWORD")
    publishingType = io.github.danielliu1123.deployer.PublishingType.AUTOMATIC
    sign {
        secretKey = System.getenv("GPG_SECRET_KEY")
        passphrase = System.getenv("GPG_PASSPHRASE")
    }
}
```

## Overview

Maven Central lacks an official Gradle deployment plugin. This plugin provides an opinionated, streamlined solution based on proven deployment practices.

**Key difference**: Snapshot and release deployments follow different workflows:
- **Snapshots**: Direct upload via `maven-publish` plugin with credentials
- **Releases**: Stage artifacts locally → Sign with GPG → Upload bundle via API (requires GPG key)

## Configuration

Create `gradle/deploy.gradle` in your project root:

```groovy
apply plugin: "java-library"
apply plugin: "maven-publish"

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        maven(MavenPublication) {
            from components.java
        }
    }

    repositories {
        maven {
            if (project.version.toString().endsWith("-SNAPSHOT")) {
                url = "https://central.sonatype.com/repository/maven-snapshots"
                credentials {
                    username = System.getenv("MAVENCENTRAL_USERNAME")
                    password = System.getenv("MAVENCENTRAL_PASSWORD")
                }
            } else {
                url = layout.buildDirectory.dir("repo")
            }
        }
    }
}
```

Apply to modules you want to publish:

```groovy
apply from: "${rootDir}/gradle/deploy.gradle"
```

## Usage

### Deploy Snapshot

```shell
export MAVENCENTRAL_USERNAME=your_username
export MAVENCENTRAL_PASSWORD=your_password
./gradlew publish -Pversion=1.0.0-SNAPSHOT
```

### Deploy Release

```shell
# Step 1: Stage artifacts
./gradlew publish -Pversion=1.0.0

# Step 2: Sign and upload to Maven Central
export MAVENCENTRAL_USERNAME=your_username
export MAVENCENTRAL_PASSWORD=your_password
export GPG_SECRET_KEY=$(< path/to/private.gpg)
export GPG_PASSPHRASE=your_passphrase
./gradlew deploy -Pversion=1.0.0
```

### GitHub Actions Setup

```shell
gh secret set MAVENCENTRAL_USERNAME --body "your_username"
gh secret set MAVENCENTRAL_PASSWORD --body "your_password"
gh secret set GPG_SECRET_KEY < path/to/private.gpg
gh secret set GPG_PASSPHRASE --body "your_passphrase"
```

## License

The MIT License.
