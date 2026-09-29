// Gradle build for people who have Maven Central access.
// The zero-dependency path (scripts/run.sh + kotlinc) is the one exercised
// while building this repo; see README "Build paths".
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

// No test-framework dependency on purpose: the suite is a plain Kotlin
// program (src/test/kotlin) so it runs identically under kotlinc and Gradle.
sourceSets {
    main { kotlin.srcDir("src/main/kotlin") }
    test { kotlin.srcDir("src/test/kotlin") }
}

application { mainClass.set("ledger.ReplayKt") }

tasks.register<JavaExec>("suite") {
    group = "verification"
    description = "Runs the ledger acceptance suite (one test fails by design)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ledger.SuiteKt")
    if (project.hasProperty("skipKnownGap")) args("--skip-known-gap")
}
