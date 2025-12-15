# Quick Start Example

Package one module into one bundle and publish to Maven Central.

## Deploy Snapshot

```shell
export MAVENCENTRAL_USERNAME=your_username
export MAVENCENTRAL_PASSWORD=your_password
./gradlew :examples:quick-start:publish -Pversion=1.0.0-SNAPSHOT
```

## Deploy Release

```shell
# Step 1: Stage artifacts
export GPG_SECRET_KEY=$(< path/to/private.gpg)
export GPG_PASSPHRASE=your_passphrase
./gradlew :examples:quick-start:publish -Pversion=1.0.0

# Step 2: upload
export MAVENCENTRAL_USERNAME=your_username
export MAVENCENTRAL_PASSWORD=your_password
./gradlew :examples:quick-start:deploy
```

## GitHub Actions Setup

```shell
gh secret set MAVENCENTRAL_USERNAME --body "your_username"
gh secret set MAVENCENTRAL_PASSWORD --body "your_password"
gh secret set GPG_SECRET_KEY < path/to/private.gpg
gh secret set GPG_PASSPHRASE --body "your_passphrase"
```
