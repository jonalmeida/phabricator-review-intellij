import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("com.ncorti.ktfmt.gradle") version "0.23.0"
}

ktfmt {
    // Equivalent to running `ktfmt --kotlinlang-style` on the CLI: official
    // Kotlin style (4-space indent, 100 col limit, etc.).
    kotlinLangStyle()
}

group = providers.gradleProperty("pluginGroup").get()

version = providers.gradleProperty("pluginVersion").get()

val javaVersion = providers.gradleProperty("javaVersion").get().toInt()
val platformType = providers.gradleProperty("platformType").get()
val platformVersion = providers.gradleProperty("platformVersion").get()

kotlin { jvmToolchain(javaVersion) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(javaVersion)) } }

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        create(platformType, platformVersion)
        testFramework(TestFrameworkType.Platform)
    }

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("junit:junit:4.13.2") // BasePlatformTestCase still pulls JUnit3/4 in places
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }

    pluginVerification { ides { recommended() } }
}

// The `live` JUnit tag is opt-in. It is included when the user invokes the
// `liveTest` task (which simply re-routes through `test` with the right
// include-tags) or passes -PincludeLive=true. We toggle by inspecting the
// task graph before `test` is executed.
val liveTestsRequested =
    gradle.startParameter.taskNames.any { it == "liveTest" } ||
        providers.gradleProperty("includeLive").orNull == "true"

tasks {
    test {
        useJUnitPlatform { if (liveTestsRequested) includeTags("live") else excludeTags("live") }
    }

    register("liveTest") {
        description =
            "Runs the JUnit `live` tag — live integration tests against real Phabricator. " +
                "Reads .phabricator_token from the repo root; skips when absent."
        group = "verification"
        dependsOn(test)
    }

    wrapper { gradleVersion = "9.0.0" }
}
