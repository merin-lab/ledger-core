# WORKLOG

Timestamps are Asia/Dubai (UTC+4), taken from the build machine clock with `date` at the time of each entry.
This repository was built by Claude (an AI assistant) working in a sandboxed Linux container on Merin's behalf; the log records what actually happened in that session, including dead ends.

| Time | Entry |
|---|---|
| 2026-09-29 10:36 | Read brief. Toolchain check: JDK 21, Gradle 8.14.3, no kotlinc, no `gh` CLI. |
| 2026-09-29 10:37 | Scaffolded Gradle Kotlin project. `gradle run` failed: plugins.gradle.org and repo.maven.apache.org return 403 from the sandbox egress proxy. Moving plugin resolution to mavenCentral() did not help (same host blocked). |
| 2026-09-29 10:39 | Found github.com reachable. Downloaded the standalone kotlin-compiler-2.0.21.zip from JetBrains' GitHub release. JUnit Jupiter jars are not obtainable here, so the suite becomes a zero-dependency Kotlin program (see REJECTED.md, "JUnit 5"). Gradle build kept for machines with Maven Central, plus `scripts/run.sh` that needs only kotlinc. |
