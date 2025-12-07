# Maven Deployer

A Gradle plugin that provides a unified (single) way to upload artifacts to Maven Central.

## Quick Start

Add the plugin to your _root project_'s `build.gradle` file:

```shell
plugins {
    id "io.github.danielliu1123.deployer" version "<latest>"
}

deploy {
    dir = file("${rootDir}/build/staging-deploy")
    username = System.getenv("MAVENCENTRAL_USERNAME")
    password = System.getenv("MAVENCENTRAL_PASSWORD")
    sign {
        secretKey = System.getenv("GPG_SECRET_KEY")
        passphrase = System.getenv("GPG_PASSPHRASE")
    }
}
```

## License

The MIT License.
