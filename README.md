# Maven Deployer

Maven Central lacks an official Gradle deployment plugin. 
This plugin provides an opinionated, streamlined solution based on proven deployment practices.

Publish snapshot and release follow different workflows:
- **Snapshots**: Direct upload via `maven-publish` plugin with credentials
- **Releases**: Stage artifacts locally (Sign with GPG) → Upload bundle via [Publisher API](https://central.sonatype.org/publish/publish-portal-api/)

## Quick Start

List the staging directories of the modules you intend to publish. The example uses `:library`. For a single-project build, use the root project's `layout.buildDirectory`.

Apply the plugin to your _root_ `build.gradle`:

```groovy
plugins {
    id "io.github.danielliu1123.deployer" version "+"
}

deploy {
    // dirs to upload, they will all be packaged into one bundle
    dirs = [project(":library").layout.buildDirectory.dir("repo").get().asFile]
    releaseVersion = providers.gradleProperty("releaseVersion").orElse("")
    username = System.getenv("MAVENCENTRAL_USERNAME")
    password = System.getenv("MAVENCENTRAL_PASSWORD")
    publishingType = io.github.danielliu1123.deployer.PublishingType.AUTOMATIC
}
```

Create `${rootDir}/gradle/deploy.gradle`:

```groovy
apply plugin: "java-library"
apply plugin: "maven-publish"
apply plugin: "signing"

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        register("maven", MavenPublication) {
            from components.java
        }
        // Add POM metadata, license, developers, scm, etc.
    }

    repositories {
        maven {
            if (version.toString().endsWith("-SNAPSHOT")) {
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

signing {
    if (!version.toString().endsWith('-SNAPSHOT')) {
        def secretKey = System.getenv("GPG_SECRET_KEY")
        def passphrase = System.getenv("GPG_PASSPHRASE")
        useInMemoryPgpKeys(secretKey, passphrase)
        sign publishing.publications.maven
    }
}
```

Apply to modules you want to publish:

```groovy
apply from: "${rootDir}/gradle/deploy.gradle"
```

### Deploy Snapshot

```shell
export MAVENCENTRAL_USERNAME=your_username
export MAVENCENTRAL_PASSWORD=your_password
./gradlew publish -Pversion=1.0.0-SNAPSHOT
```

### Deploy Release

```shell
# Step 1: Stage artifacts and sign
export GPG_SECRET_KEY=$(< path/to/private.gpg)
export GPG_PASSPHRASE=your_passphrase
./gradlew clean
./gradlew publish -Pversion=1.0.0

# Step 2: Upload
export MAVENCENTRAL_USERNAME=your_username
export MAVENCENTRAL_PASSWORD=your_password
./gradlew deploy -PreleaseVersion=1.0.0
```

`releaseVersion` limits the bundle to that version in every configured repository. Each repository must contain a matching staged POM. If you omit `releaseVersion` from the plugin configuration, `deploy` bundles all files in the configured repositories.

`AUTOMATIC` reports when Central Portal accepts the bundle. Set `publishingType` to `WAIT_FOR_PUBLISHED` to wait for Central Portal to confirm publication.

## GitHub Actions Setup

```shell
gh secret set MAVENCENTRAL_USERNAME --body "your_username"
gh secret set MAVENCENTRAL_PASSWORD --body "your_password"
gh secret set GPG_SECRET_KEY < path/to/private.gpg
gh secret set GPG_PASSPHRASE --body "your_passphrase"
```

## Examples

- [Quick Start](examples/quick-start/README.md)
- [Multi Modules](examples/multi-modules/README.md)

## License

The MIT License.
