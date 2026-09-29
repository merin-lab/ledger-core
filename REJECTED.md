# REJECTED

Part 1 lists the acceptance criteria I refuse, with the arithmetic that disproves each one. Part 2 lists the approaches I abandoned during the build.

Every refused criterion has a test in `src/test/kotlin/ledger/Suite.kt` (names starting `R`). Each test pins the behaviour I implemented in its place, so the refusal can be checked by running the suite.

## Scorecard

| # | Criterion (abridged) | Verdict |
|---|---|---|
| 1 | Day 2 closing, at end of Day 5, before fees = AED −370.00 | **Accepted** (test AC1) |
| 2 | E7 causes exactly one overdraft fee, on Day 2 | **Rejected** |
| 3 | Day 4 settlement of Auth-A must be accepted | **Accepted** (test AC3) |
| 4 | Settlement with unknown auth ID must be rejected; funds must not leave | **Rejected** |
| 5 | If Auth-B is approved, hold reduces available not ledger | **Accepted**, with a caveat (tests AC5 ×3) |
| 6 | After E9, all balances and fees return to pre-E7 values | **Rejected** |
| 7 | The three BHD instalments must each be 3.334 | **Rejected** |
| 8 | If rounded accruals don't sum to the total, discard the remainder | **Rejected** |

---

## Criterion 2: "E7 causes exactly one overdraft fee to be assessed, on Day 2". REJECTED

E7 is a debit of 620.00 with a Day 2 value date. It arrives on Day 5. The brief's fee rule applies per day to *that day's* closing balance, counting all entries with value date ≤ that day. At EOD 5 the engine re-walks value days 1..5 in ascending order with everything it now knows:

| Value day | Entries now known (vd ≤ day) | Closing before fee | Fee? | Closing after fee |
|---|---|---|---|---|
| D1 | +1200 −950 | 250.00 | no | 250.00 |
| D2 | + E7 −620 | **−370.00** | **yes** | −395.00 |
| D3 | + E4 +400 | 5.00 | no | 5.00 |
| D4 | + E5 −185, E6 −180 | **−360.00** | **yes** | −385.00 |
| D5 | (nothing new) | **−385.00** | **yes** | −410.00 |

Before E7 arrived, every one of these days was positive (D4 was 285.00), so all three fees are caused by E7. The claim of "exactly one" only holds if the rule is applied to E7's own value date and nowhere else. That reading contradicts the brief's "once per day per account when that day's closing … is negative".

The result does not depend on my contested decision on criterion 4. Under the REJECT policy D4 is −180.00 and D5 is −205.00, so there are still three fees. The test `R2` asserts both variants.

---

## Criterion 4: "Any settlement referencing an authorization ID not present in the ledger must be rejected and the funds must not leave the account". REJECTED

I refuse it for three reasons:

1. **The ledger cannot make that promise.** A card settlement is a clearing record: the scheme has already debited the issuer. Refusing to book the settlement does not stop the funds leaving. It only makes the customer ledger disagree with the settlement account, so the books no longer reconcile. "Funds must not leave the account" is a requirement on the network, not on a ledger that sits downstream of it.
2. **Settlements without a matching auth are routine.** Offline or stand-in transactions, force-posts, auths purged before capture, and ID mismatches between acquirer and issuer all produce them. The standard handling is to post the settlement, flag it as an exception, and send it to disputes or chargeback. The customer is protected by the dispute process, not by a ledger that hides the debit.
3. **Read literally, it breaks criterion 3.** Authorizations are holds and move no money, so they are never *in the ledger*. In this design they live in a separate append-only authorization log. Taken word for word, "not present in the ledger" applies to Auth-A as well, and would reject the settlement that criterion 3 says must be accepted.

**Implemented instead:** `UnmatchedSettlementPolicy.FORCE_POST_AND_FLAG` (the default) books the 180.00 debit and raises `[EXCEPTION] UNMATCHED_SETTLEMENT` on Day 4. The same path handles a settlement against a *declined* or *already-settled* auth.

**Kept as an option:** `UnmatchedSettlementPolicy.REJECT` (`scripts/run.sh replay --reject-unmatched`). It is correct only if this ledger sits *upstream* of payment (for example, account-to-account transfers it initiates itself). Its effect on the scenario: D4 closes at 465.00 instead of 285.00, and the fee count stays at 3.

---

## Criterion 5: "If Auth-B is approved, its hold reduces available balance but not ledger balance". ACCEPTED, with a caveat

As a conditional statement it is true, and the design enforces it: holds live in the authorization log, never in the journal. Two tests cover it: Auth-A on Day 2 (ledger 250.00, available 50.00) and a variant stream without E7 in which Auth-B *is* approved (ledger 285.00, available 195.00).

**The caveat:** in the actual stream Auth-B is **declined**. E7 comes before E8 on Day 5, so when E8 is decided the ledger is −335.00 and available after the hold would be −425.00. The antecedent is false, and "Auth-B is never settled inside the window" is true only trivially. The test `AC5 … DECLINED` pins this.

---

## Criterion 6: "After E9, all balances and fees return to their pre-E7 values". REJECTED

| | Before E7 (after E6) | After E9 |
|---|---|---|
| D2 closing | 250.00 | **225.00** |
| D4 closing | 285.00 | **235.00** |
| Overdraft fees | 0 | **3 × 25.00 = 75.00** |
| Auth-B | not yet requested | **declined**, and the decision stands |
| E7 in the journal | no | **yes**, followed by its reversal E9 |

1. **The ledger is append-only.** E9 *adds* an offsetting +620.00. It does not remove E7, and nothing removes the three fees booked at EOD 5. Balances can be *netted* back. Records cannot be *returned* to a previous state.
2. **The fees were correctly assessed when they were booked.** The rule is "charge when negative"; the brief has no rule for refunding a fee when a later posting cures the overdraft.
3. **An authorization decision is a point-in-time fact.** The merchant was told "no". Replaying history cannot un-decline Auth-B.

The first point is non-negotiable. I think the second point is a *real weakness* of my design, so I made it the deliberately failing test (`KnownGapTest.kt`). A compensating fee-refund entry would be append-only and customer-fair, and the criterion's intent (making the customer whole after an erroneous posting) is reasonable. Its wording ("all … return", including Auth-B) is not achievable.

---

## Criterion 7: "The three BHD instalments in E10 must each be BHD 3.334". REJECTED

3 × 3.334 = **10.002**, not 10.000. Posting that would create BHD 0.002 from nothing: the ledger would hold more than the event credited. BHD has 3 decimals, so 10.000 / 3 cannot be split into equal amounts. The criterion's own number is the result of rounding 3.3333… *up* and ignoring the consequence.

**Implemented instead:** split in minor units with the remainder distributed one unit at a time. 10000 fils / 3 = 3333 remainder 1, giving **3.334 + 3.333 + 3.333 = 10.000**. The instalments are as equal as the currency allows (differing by at most 1 fil). The allocation order (first instalment takes the extra fil) is recorded in AMBIGUITIES.md. `Money.split` is tested exhaustively for 0..2000 minor units × 1..7 parts.

---

## Criterion 8: "If the rounded daily interest accruals do not sum to the capitalized total, the remainder is discarded". REJECTED

The brief's non-negotiable rule is: *"The rounded daily accruals must sum exactly to the capitalized total."* The criterion assumes that rule can be broken and proposes throwing the difference away. That contradicts the rule rather than implementing it. Discarding a difference also leaves the journal's interest credit unreconciled with the accrual records, so auditors cannot tie them together.

The mismatch happens in this very scenario if you compute it the wrong way:

| Day | Closing | Unrounded 0.04% | Rounded (HALF_EVEN, 2dp) |
|---|---|---|---|
| D1 | 250.00 | 0.1000 | 0.10 |
| D2 | 225.00 | 0.0900 | 0.09 |
| D3 | 625.00 | 0.2500 | 0.25 |
| D4 | 235.00 | 0.0940 | 0.09 |
| D5 | 210.00 | 0.0840 | 0.08 |
| D6 | 210.00 | 0.0840 | 0.08 |
| **Sum** | | **0.7020 → rounds to 0.70** | **0.69** |

**Implemented instead:** the capitalized total is *defined* as the sum of the rounded daily accruals (0.69). The rule holds by construction and there is never a remainder. Test `R8` asserts the booked 0.69 and also shows that round(sum) would give 0.70.

---

# Approaches abandoned mid-build

Items 1–4 and 7 were tried in code or tooling and failed or were removed. Items 5–6 were design options I weighed and dropped *before* coding. I say which is which so this list isn't padded.

1. **JUnit 5 as the test framework (tried, abandoned).** The build sandbox's egress proxy returns 403 for `repo.maven.apache.org` and `plugins.gradle.org`, so `./gradlew test` could never run here. I didn't want to submit a suite I had never executed, so I replaced JUnit with a ~40-line stdlib harness (`Harness.kt`) that runs identically under `kotlinc` and Gradle. The cost is no IDE test-tree integration; you run `SuiteKt` as a normal program instead.
2. **`jvmToolchain(21)` in Gradle (tried, removed).** It would force Gradle to find or auto-provision JDK 21. On an Android Studio machine whose bundled JBR is 17, that fails without the foojay resolver plugin. Replaced it with bytecode target 17, compiled by whatever JDK runs Gradle.
3. **Generating the Gradle wrapper normally (tried, failed).** `gradle wrapper` checks the distribution URL over the network. I generated it with `--no-validate-url` in a scratch directory, then re-enabled validation in the committed properties.
4. **An `OPENING` ledger entry type (coded, removed).** Both opening balances are zero, so posting them would add zero-amount entries to the journal. The opening balance is now the seed of the balance fold.
5. **Point-in-time interest accrual (design option, rejected).** This option accrues each EOD on the balance *as known that evening*. It gives ACC-001 0.10+0.10+0.26+0.11+0.00+0.08 = **0.65** (Day 5 was −410.00 that evening), and ACC-002 only **0.004**, because E10 was unknown at EOD 5. It is internally consistent, but it contradicts the value-dated fee rule: fees are re-evaluated retroactively by value date, so interest must be too, or the same day's balance means two different things in one engine. I chose final value-dated balances (0.69 and 0.008).
6. **A mutable `state` field on an Authorization object (design option, rejected).** Updating `auth.state = SETTLED` is a mutation of an event record. I replaced it with an append-only authorization log (`AuthDecision`, `AuthSettled`) and state derived on read.
7. **Sorting the stream by event day before replay (considered, then rejected when E10 showed up late).** Sorting would have made E10 arrive "on time" on Day 5. The brief says "replayed in this order", and a late-arriving event is a real production case the engine must handle. I kept the order and added `LATE_EVENT` handling instead.
