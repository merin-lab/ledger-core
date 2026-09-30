# ledger-core

An in-memory, append-only account ledger core in Kotlin. It has no web layer, persistence, UI or database.
A replay program feeds it the brief's event stream (E1–E10, Day 1–6) and prints, for each day, the closing ledger balance, fee assessments, authorization states, and errors. A test suite checks every rule and acceptance criterion.

| Doc | What's in it |
|---|---|
| [REJECTED.md](REJECTED.md) | The 5 acceptance criteria I refuse (2, 4, 6, 7, 8) with the arithmetic, plus approaches abandoned mid-build |
| [AMBIGUITIES.md](AMBIGUITIES.md) | 42 ambiguities, the options, and what I chose |
| [NUMBERS.md](NUMBERS.md) | Every constant, and why that value rather than half of it |
| [WORKLOG.md](WORKLOG.md) | Timestamped build log, including dead ends |
| [`KnownGapTest.kt`](src/test/kotlin/ledger/KnownGapTest.kt) | The one test that fails against this design, annotated |

## Running it

Requires JDK 17 or newer (Android Studio's bundled JBR is fine).

### Option A: Android Studio or IntelliJ (Gradle)

1. **File → Open…** → select this folder → **Trust Project**. Wait for the Gradle sync to finish (the first sync downloads Gradle 8.14.3 and Kotlin 2.0.21).
2. **Replay:** open `src/main/kotlin/ledger/Replay.kt` → click the green ▶ next to `fun main` → **Run 'ReplayKt'**.
3. **Test suite:** open `src/test/kotlin/ledger/Suite.kt` → green ▶ next to `fun main` → **Run 'SuiteKt'**.

Or from the terminal (the **Terminal** tab in the IDE):

```bash
./gradlew run                              # replay report       (Windows: gradlew.bat run)
./gradlew suite                            # full suite: exits 1, the known-gap test fails BY DESIGN
./gradlew suite -PskipKnownGap             # suite without the known gap: exits 0
./gradlew run --args="--reject-unmatched"  # replay with the alternative settlement policy
```

### Option B: no Gradle (the path this repo was built and verified with)

The build sandbox could not reach Maven Central, so the code was compiled and run with the standalone Kotlin compiler (`kotlinc`, 2.0.21).

```bash
scripts/run.sh replay
scripts/run.sh replay --reject-unmatched
scripts/run.sh suite                       # exit 1 by design (known gap)
scripts/run.sh suite --skip-known-gap      # exit 0
```

> **Honesty note:** Option B is the one exercised during the build. The Gradle files follow standard Kotlin/JVM conventions but could not be executed in the build sandbox. If the first Gradle sync complains, check that Android Studio's Gradle JDK is set to 17 or newer (Settings → Build, Execution, Deployment → Build Tools → Gradle → *Gradle JDK*).

## Reading the replay output

Each day prints one block per account:

```
DAY 5 - end of day    events processed: E7, E8
ACC-001 [AED]
  closing ledger balance : -410.00        <- all entries with value date <= 5, as known tonight
  active holds           : 0.00   available: -410.00
  value-day view (now)   : D1 250.00  D2 -395.00  D3 5.00  D4 -385.00  D5 -410.00
                                          ^ every earlier day restated with what is known NOW
  fee assessments        :                <- fees booked during THIS end-of-day run
    - AED 25.00 for value day 2 (closing before fee -370.00)
    ...
  authorizations         :
    - Auth-B: DECLINED (requested 90.00, no hold; decided day 5 by E8)
errors / exceptions:
  [WARNING] AUTH_DECLINED E8 ACC-001: ...
```

* **closing ledger balance** is the point-in-time figure: what the bank would have reported that evening. Holds are never part of it.
* **value-day view** shows why fees appear on past days. E7 arrived on Day 5 carrying value date 2, so D2 went negative *retroactively*.
* **fee assessments** lists fees booked tonight. Each fee's value date is the day whose balance triggered it, which can be earlier than today.
* **interest** appears only on Day 6: the daily accrual schedule and the single capitalized credit, which equals the sum of the rounded accruals.
* **errors / exceptions** has three severities. `ERROR`: refused, nothing booked. `EXCEPTION`: booked, but needs human review (Auth-Z). `WARNING`: normal but notable (declines, late events).
* After Day 6 come the **FINAL RESTATEMENT** (every value day with full knowledge) and the **JOURNAL**, the complete append-only entry list with value date *and* posting day.

### Expected results

| | D1 | D2 | D3 | D4 | D5 | D6 |
|---|---|---|---|---|---|---|
| ACC-001 closing, as printed that evening | 250.00 | 250.00 | 650.00 | 285.00 | −410.00 | 210.69 |
| ACC-001 restated at window close | 250.00 | 225.00 | 625.00 | 235.00 | 210.00 | 210.69 |
| ACC-002 closing, as printed that evening | 0.000 | 0.000 | 0.000 | 0.000 | 0.000 | 10.008 |

Fees: 3 × AED 25.00 (value days 2, 4, 5, all assessed at EOD 5). Auth-A: approved, then settled for 185.00. Auth-Z: force-posted and flagged. Auth-B: declined. Interest: AED 0.69 and BHD 0.008.

## Reading the suite output

```
PASS  AC1 ...        <- acceptance criteria I accept
PASS  R2 ...         <- criteria I REJECT: the test pins what I do instead
PASS  ...            <- rules and invariants (append-only, precision, idempotency, ...)
FAIL (known gap, see KnownGapTest.kt)  GAP a reversed erroneous posting should leave the customer whole
      -> net overdraft fees on ACC-001 after E7 was reversed: expected <AED 0.00> but was <AED -75.00>

27 passed, 1 failed (1 of them the documented known gap), 0 skipped
```

## Design in one screen

```
src/main/kotlin/ledger/
  Money.kt         BigDecimal locked to currency precision; split() in minor units
  Model.kt         input events, immutable LedgerEntry, auth log records, notices
  Policy.kt        every business constant + UnmatchedSettlementPolicy
  LedgerEngine.kt  process(event) / finish(); end-of-day: fees -> interest -> snapshot
  Scenario.kt      the brief's accounts and E1..E10 in the brief's order
  Report.kt        text report
  Replay.kt        main()
src/test/kotlin/ledger/
  Harness.kt       ~40-line assertion harness (no JUnit; see REJECTED.md)
  Suite.kt         main(): 27 tests
  KnownGapTest.kt  the one deliberately failing test
```

* **Append-only everywhere.** Journal entries, authorization decisions, settlements and notices go into lists that only grow. Authorization *state* is derived from the log on read, never stored. The engine hands out copies. A test checks that every earlier snapshot is a prefix of every later one.
* **Holds are not ledger entries.** They move no money, so they live in the authorization log and affect only `available`.
* **Three clocks.** Every entry carries a `valueDate` (for balances) and a `postingDay` (when it was learned). `balance(account, asOfValueDay, knownAt)` can answer "what did we think D2 was on the evening of Day 5?".
* **Money cannot be imprecise.** A `Money` whose scale doesn't match its currency cannot be constructed. The only rounding entry point is `Money.ofRounded(…, mode)`, used once, for interest.
