# WORKLOG

Timestamps are Asia/Dubai (UTC+4), taken from the build machine clock with `date` at the time of each entry.
This repository was built by Claude (an AI assistant) working in a sandboxed Linux container on Merin's behalf; the log records what actually happened in that session, including dead ends.

| Time | Entry |
|---|---|
| 2026-09-29 10:36 | Read brief. Toolchain check: JDK 21, Gradle 8.14.3, no kotlinc, no `gh` CLI. |
| 2026-09-29 10:37 | Scaffolded Gradle Kotlin project. `gradle run` failed: plugins.gradle.org and repo.maven.apache.org return 403 from the sandbox egress proxy. Moving plugin resolution to mavenCentral() did not help (same host blocked). |
| 2026-09-29 10:39 | Found github.com reachable. Downloaded the standalone kotlin-compiler-2.0.21.zip from JetBrains' GitHub release. JUnit Jupiter jars are not obtainable here, so the suite becomes a zero-dependency Kotlin program (see REJECTED.md, "JUnit 5"). Gradle build kept for machines with Maven Central, plus `scripts/run.sh` that needs only kotlinc. |
| 2026-09-29 10:42 | Money type: BigDecimal locked to currency precision (AED 2, BHD 3); construction throws instead of silently rounding. Instalment split works in minor units, remainder spread one unit at a time. |
| 2026-09-29 10:44 | Engine. Authorizations kept OUT of the ledger (a hold moves no money) in their own append-only log; state is derived, never stored, so "no record mutated" holds for auths too. Fees walk every value day ascending at each EOD so back-valued postings (E7) can trigger fees on past days. Interest computed on final value-dated balances at window close. Decided default for settlement without an auth = force-post + EXCEPTION (reasoning in REJECTED.md, criterion 4); REJECT kept as a policy switch. First compile failed: returned a LedgerEntry from a Unit function in settle(); fixed. |
