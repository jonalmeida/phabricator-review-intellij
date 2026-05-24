# phabricator-review-intellij

JetBrains IDE plugin for browsing and reviewing [Mozilla Phabricator](https://phabricator.services.mozilla.com/) differential revisions. Port of [phabricator-review-vscode](https://github.com/jonalmeida/phabricator-review-vscode).

## Status

**Phase 1 (read-only) in progress.** Auth + revisions tree + diff viewer. Inline comments, submit-from-commit flow, and the full revision overview panel land in later phases.

## Build

```bash
./gradlew buildPlugin       # produces build/distributions/*.zip
./gradlew runIde            # launches a sandbox IntelliJ Community with the plugin loaded
./gradlew test              # JUnit 5 unit tests (excludes `live` tag)
./gradlew liveTest          # integration tests against real Phabricator (see below)
./gradlew verifyPlugin      # plugin verifier against recommended IDE versions
```

Gradle will auto-provision JDK 21 via toolchains; you do not need to install it yourself.

## Phabricator token (`.phabricator_token`)

`./gradlew liveTest` and several manual end-to-end test steps read a Conduit API token from a file at the repo root named `.phabricator_token`. **This file is gitignored — never commit it.**

Get a token at <https://phabricator.services.mozilla.com/conduit/login/> and save it:

```bash
echo "api-xxxxxxxxxxxxxxxxxxxxxxxxxx" > .phabricator_token
```

If the file is missing, `liveTest` skips its tests via JUnit's `assumeTrue`.

## Target platform

IntelliJ Platform 2024.3 (build 243.\*), Java 21, Kotlin 2.0.
