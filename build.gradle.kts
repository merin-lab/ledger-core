// Gradle build (Android Studio / IntelliJ / command line). Needs Maven Central + Gradle Plugin Portal.
// The zero-dependency path (scripts/run.sh + kotlinc) is the one exercised while building
// this repo, because the build sandbox could not reach Maven Central. See README "Build paths".
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

// No toolchain pin: compile with whatever JDK (17+) runs Gradle, e.g. Android Studio's bundled JBR.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

application { mainClass.set("ledger.ReplayKt") }

// The suite is a plain Kotlin program (src/test/kotlin/ledger/Suite.kt), not JUnit.
// Disable the default JUnit `test` task so `./gradlew build` does not report "no tests found".
tasks.named<Test>("test") { enabled = false }

tasks.register<JavaExec>("suite") {
    group = "verification"
    description = "Runs the ledger test suite. ONE test fails by design; -PskipKnownGap excludes it."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ledger.SuiteKt")
    if (project.hasProperty("skipKnownGap")) args("--skip-known-gap")
}
