package ledger

import ledger.Currency.AED
import ledger.Currency.BHD
import kotlin.system.exitProcess

/**
 * Runs every test. Exit code 0 only if everything passes.
 * The one known-gap test (KnownGapTest.kt) FAILS BY DESIGN; pass --skip-known-gap to exclude it.
 */
fun main(args: Array<String>) {
    val registry = Registry()
    acceptanceTests(registry)
    rejectedCriteriaTests(registry)
    invariantTests(registry)
    knownGapTests(registry)

    val skipGap = "--skip-known-gap" in args
    var passed = 0; var failed = 0; var gapFailed = 0; var skipped = 0
    for (case in registry.cases) {
        if (case.knownGap && skipGap) { println("SKIP  ${case.name}"); skipped++; continue }
        try {
            case.body()
            println("PASS  ${case.name}"); passed++
        } catch (e: Throwable) {
            val tag = if (case.knownGap) "FAIL (known gap, see KnownGapTest.kt)" else "FAIL"
            println("$tag  ${case.name}\n      -> ${e.message}")
            failed++; if (case.knownGap) gapFailed++
        }
    }
    println()
    println("$passed passed, $failed failed ($gapFailed of them the documented known gap), $skipped skipped")
    exitProcess(if (failed == 0) 0 else 1)
}

private fun feesOn(engine: LedgerEngine, acct: String) =
    engine.entries().filter { it.accountId == acct && it.type == EntryType.FEE }

private fun noFeeSchedule(engine: LedgerEngine, acct: String) =
    engine.notices().filter { it.code == "NO_FEE_SCHEDULE" && it.accountId == acct }

/** Reads N from a NO_FEE_SCHEDULE message of the form "value day N closed at ...". Fails loudly if the format changes. */
private fun valueDayOf(n: Notice): Int =
    Regex("""^value day (\d+) """).find(n.message)?.groupValues?.get(1)?.toInt()
        ?: throw AssertionError("cannot read value day from NO_FEE_SCHEDULE message: '${n.message}'")

private fun noFeeScheduleCountsByValueDay(engine: LedgerEngine, acct: String): Map<Int, Int> =
    noFeeSchedule(engine, acct).groupingBy { valueDayOf(it) }.eachCount().toSortedMap()

private fun eventsUntil(id: String) = Scenario.events.takeWhile { it.id != id } + Scenario.events.first { it.id == id }

// ----------------------------------------------------------------------------
// Acceptance criteria that are CORRECT and therefore implemented.
// ----------------------------------------------------------------------------
private fun acceptanceTests(r: Registry) {
    r.test("AC1 Day 2 closing, as known at end of Day 5, before any fee, is AED -370.00") {
        val engine = Scenario.replay()
        assertEq(aed("-370.00"), engine.balance("ACC-001", asOfValueDay = 2, knownAt = 5, exclude = setOf(EntryType.FEE)))
        val day5 = engine.reports().first { it.day == 5 }.accounts.first { it.account.id == "ACC-001" }
        assertEq(aed("-370.00"), day5.feesAssessed.first { it.forValueDay == 2 }.balanceBeforeFee, "fee trigger balance")
        // and it was NOT negative when Day 2 was originally closed (E7 had not arrived)
        assertEq(aed("250.00"), engine.balance("ACC-001", asOfValueDay = 2, knownAt = 2))
    }

    r.test("AC3 Day 4 settlement of Auth-A is accepted (185.00 against a 200.00 hold)") {
        val engine = Scenario.replay()
        val posted = engine.entries().single { it.sourceEventId == "E5" }
        assertEq(EntryType.SETTLEMENT, posted.type); assertEq(aed("-185.00"), posted.amount); assertEq(4, posted.valueDate)
        val auth = engine.authView("ACC-001", "Auth-A")!!
        assertEq(AuthState.SETTLED, auth.state); assertEq(aed("185.00"), auth.settledAmount)
        assertTrue(engine.notices().none { it.eventId == "E5" }, "no notice for E5")
    }

    r.test("AC5 an approved hold reduces available but not ledger (Auth-A on Day 2)") {
        val engine = Scenario.replay(events = eventsUntil("E3"), finish = false)
        assertEq(aed("250.00"), engine.balance("ACC-001", 2), "ledger")
        assertEq(aed("200.00"), engine.activeHolds("ACC-001"), "holds")
        assertEq(aed("50.00"), engine.available("ACC-001"), "available")
    }

    r.test("AC5 if Auth-B WERE approved (stream without E7) its hold hits available only") {
        val events = Scenario.events.filter { it.id in setOf("E1", "E2", "E3", "E4", "E5", "E6", "E8") }
        val engine = Scenario.replay(events = events, finish = false)
        assertEq(AuthState.APPROVED_ACTIVE, engine.authView("ACC-001", "Auth-B")!!.state)
        assertEq(aed("285.00"), engine.balance("ACC-001", 5), "ledger untouched by hold")
        assertEq(aed("195.00"), engine.available("ACC-001"), "available = 285.00 - 90.00")
    }

    r.test("AC5 in the actual stream Auth-B is DECLINED (E7 already pushed available to -335.00)") {
        val engine = Scenario.replay()
        assertEq(AuthState.DECLINED, engine.authView("ACC-001", "Auth-B")!!.state)
        assertEq(aed("0.00"), engine.activeHolds("ACC-001"))
    }
}

// ----------------------------------------------------------------------------
// Criteria REFUSED in REJECTED.md. Each test pins the behaviour we chose instead.
// ----------------------------------------------------------------------------
private fun rejectedCriteriaTests(r: Registry) {
    r.test("R2 E7 causes THREE overdraft fees (value days 2, 4, 5), not one") {
        val engine = Scenario.replay()
        assertEq(listOf(2, 4, 5), feesOn(engine, "ACC-001").map { it.feeForDay })
        assertTrue(feesOn(engine, "ACC-001").all { it.postingDay == 5 }, "all assessed at EOD 5, when E7 became known")
        assertEq(listOf(2, 4, 5), feesOn(Scenario.replay(Policy(unmatchedSettlement = UnmatchedSettlementPolicy.REJECT)), "ACC-001").map { it.feeForDay },
            "still three under the REJECT policy")
    }

    r.test("R4 unmatched settlement Auth-Z is force-posted and flagged, not silently refused") {
        val engine = Scenario.replay()
        assertEq(aed("-180.00"), engine.entries().single { it.sourceEventId == "E6" }.amount)
        val n = engine.notices().single { it.eventId == "E6" }
        assertEq(Severity.EXCEPTION, n.severity); assertEq("UNMATCHED_SETTLEMENT", n.code)
    }

    r.test("R4 REJECT policy switch still available and books nothing") {
        val engine = Scenario.replay(Policy(unmatchedSettlement = UnmatchedSettlementPolicy.REJECT))
        assertTrue(engine.entries().none { it.sourceEventId == "E6" }, "no posting for E6")
        assertEq("UNMATCHED_SETTLEMENT_REJECTED", engine.notices().single { it.eventId == "E6" }.code)
    }

    r.test("R6 after E9, balances and fees do NOT return to pre-E7 values") {
        val before = Scenario.replay(events = eventsUntil("E6"), finish = false)
        val after = Scenario.replay()
        assertEq(aed("250.00"), before.balance("ACC-001", 2), "pre-E7 Day 2")
        assertEq(aed("225.00"), after.balance("ACC-001", 2, exclude = setOf(EntryType.INTEREST)), "post-E9 Day 2 keeps the 25.00 fee")
        assertEq(0, feesOn(before, "ACC-001").size, "no fees pre-E7")
        assertEq(3, feesOn(after, "ACC-001").size, "fees persist after reversal (append-only)")
        assertEq(AuthState.DECLINED, after.authView("ACC-001", "Auth-B")!!.state, "Auth-B decline is not undone")
        assertTrue(after.entries().any { it.sourceEventId == "E7" }, "E7 itself is still in the ledger")
    }

    r.test("R7 E10 instalments are 3.334 + 3.333 + 3.333 = 10.000, not 3 x 3.334") {
        val engine = Scenario.replay()
        val parts = engine.entries().filter { it.sourceEventId == "E10" }.map { it.amount }
        assertEq(listOf(bhd("3.334"), bhd("3.333"), bhd("3.333")), parts)
        assertEq(bhd("10.000"), parts.sum(BHD))
        assertTrue(bhd("3.334") + bhd("3.334") + bhd("3.334") != bhd("10.000"), "3 x 3.334 = 10.002")
    }

    r.test("R8 capitalized interest equals the sum of rounded daily accruals; nothing is discarded") {
        val engine = Scenario.replay()
        val cap = engine.reports().last().accounts.first { it.account.id == "ACC-001" }.capitalization!!
        assertEq(aed("0.69"), cap.total)
        assertEq(cap.accruals.map { it.accrual }.sum(AED), cap.total)
        assertEq(cap.total, engine.entries().single { it.accountId == "ACC-001" && it.type == EntryType.INTEREST }.amount)
        // The trap: rounding the UNROUNDED sum gives 0.70, one fil off from the rounded accruals.
        val unrounded = cap.accruals.fold(java.math.BigDecimal.ZERO) { a, x -> a + x.closingBalance.amount.max(java.math.BigDecimal.ZERO) * java.math.BigDecimal("0.0004") }
        assertEq(aed("0.70"), Money.ofRounded(AED, unrounded, java.math.RoundingMode.HALF_EVEN), "round(sum) differs")
    }
}

// ----------------------------------------------------------------------------
// Rules from the brief + engine invariants.
// ----------------------------------------------------------------------------
private fun invariantTests(r: Registry) {
    r.test("Per-day closing ledger balances as printed (point-in-time)") {
        val engine = Scenario.replay()
        val closes = engine.reports().map { d -> d.accounts.first { it.account.id == "ACC-001" }.closingLedger }
        assertEq(listOf("250.00", "250.00", "650.00", "285.00", "-410.00", "210.69").map(::aed), closes)
        assertEq(bhd("10.008"), engine.reports().last().accounts.first { it.account.id == "ACC-002" }.closingLedger)
    }

    r.test("Final restated value-day balances ACC-001") {
        val engine = Scenario.replay()
        assertEq(listOf("250.00", "225.00", "625.00", "235.00", "210.00", "210.69").map(::aed), (1..6).map { engine.balance("ACC-001", it) })
    }

    r.test("Append-only: every earlier journal and notice list is a prefix of every later one") {
        val engine = LedgerEngine(Scenario.accounts)
        val snaps = ArrayList<List<LedgerEntry>>(); val noticeSnaps = ArrayList<List<Notice>>()
        Scenario.events.forEach { engine.process(it); snaps += engine.entries(); noticeSnaps += engine.notices() }
        engine.finish(); snaps += engine.entries(); noticeSnaps += engine.notices()
        for (i in 1 until snaps.size) {
            assertEq(snaps[i - 1], snaps[i].take(snaps[i - 1].size), "journal prefix at step $i")
            assertEq(noticeSnaps[i - 1], noticeSnaps[i].take(noticeSnaps[i - 1].size), "notice prefix at step $i")
        }
    }

    r.test("Append-only: callers get copies and cannot mutate the journal") {
        val engine = Scenario.replay()
        val n = engine.entries().size
        (engine.entries() as MutableList<LedgerEntry>).clear()
        assertEq(n, engine.entries().size)
    }

    r.test("Precision: every amount is stored at its currency's scale") {
        val engine = Scenario.replay()
        engine.entries().forEach { assertEq(it.amount.currency.precision, it.amount.amount.scale(), "seq ${it.seq}") }
    }

    r.test("Precision: over-precise input is rejected, never rounded") {
        assertThrows<IllegalArgumentException>("AED 1.005") { aed("1.005") }
        val engine = LedgerEngine(Scenario.accounts)
        engine.process(CreditEvent("X1", 1, "ACC-002", "10.0001", BHD, 1))
        engine.process(CreditEvent("X2", 1, "ACC-001", "5.00", BHD, 1))
        assertEq(0, engine.entries().size)
        assertEq(listOf("INVALID_AMOUNT", "INVALID_AMOUNT"), engine.notices().map { it.code })
    }

    r.test("Fee: at most one per account per value day, even across many EOD runs") {
        val engine = Scenario.replay()
        val keys = feesOn(engine, "ACC-001").map { it.feeForDay }
        assertEq(keys.distinct(), keys)
        assertTrue(feesOn(engine, "ACC-001").all { it.valueDate == it.feeForDay }, "fee value date == day assessed")
    }

    r.test("Fee: no AED fee is invented for a BHD overdraft; an error is raised instead") {
        val engine = LedgerEngine(Scenario.accounts)
        engine.process(DebitEvent("X1", 1, "ACC-002", "1.000", BHD, 1)); engine.finish()
        assertEq(0, feesOn(engine, "ACC-002").size)
        assertTrue(engine.notices().any { it.code == "NO_FEE_SCHEDULE" }, "NO_FEE_SCHEDULE raised")
    }

    // The NO_FEE_SCHEDULE notice must fire exactly once for each day whose closing balance is negative,
    // not once every night.
    // Why this needed a fix: every night, assessOverdraftFees deliberately rechecks every earlier value day
    // (so back-dated entries can make a past day negative). For AED, the booked fee stops a repeat.
    // For BHD no fee is ever booked, so nothing stopped the same day being reported again every night.
    // The fix remembers which (account, value day) pairs were already reported.
    // The notice has no dedicated value-day field, so these tests read the day from the message text
    // ("value day N ..."). If a structured field is added later, update noFeeScheduleCountsByValueDay to use it.
    r.test("Fee: NO_FEE_SCHEDULE raised exactly once per negative value day, however many nights pass") {
        val engine = LedgerEngine(Scenario.accounts)
        // ACC-002 (BHD) goes negative on value day 2 and stays negative through Day 6: 5 negative days, 5 nights.
        engine.process(DebitEvent("X1", 2, "ACC-002", "1.000", BHD, 2)); engine.finish()

        assertEq(mapOf(2 to 1, 3 to 1, 4 to 1, 5 to 1, 6 to 1), noFeeScheduleCountsByValueDay(engine, "ACC-002"),
            "NO_FEE_SCHEDULE notices per value day")
        assertEq(0, feesOn(engine, "ACC-002").size, "still no fee booked")
    }

    r.test("Fee: NO_FEE_SCHEDULE is raised on the night the day first closes negative, and never again") {
        val engine = LedgerEngine(Scenario.accounts)
        engine.process(DebitEvent("X1", 2, "ACC-002", "1.000", BHD, 2)); engine.finish()

        val first = noFeeSchedule(engine, "ACC-002").groupBy { valueDayOf(it) }.mapValues { (_, ns) -> ns.map { it.day } }
        (2..6).forEach { d -> assertEq(listOf(d), first[d], "processing day(s) on which value day $d was reported") }
    }

    r.test("Fee: NO_FEE_SCHEDULE stops for days after the overdraft is cured") {
        val engine = LedgerEngine(Scenario.accounts)
        // Negative on value days 2 and 3; a Day 4 credit brings it back to +1.000 from value day 4 onward.
        engine.process(DebitEvent("X1", 2, "ACC-002", "1.000", BHD, 2))
        engine.process(CreditEvent("X2", 4, "ACC-002", "2.000", BHD, 4))
        engine.finish()

        assertEq(mapOf(2 to 1, 3 to 1), noFeeScheduleCountsByValueDay(engine, "ACC-002"),
            "only the negative days are reported, once each")
    }

    r.test("Authorization boundary: available exactly zero after hold is approved") {
        val engine = LedgerEngine(Scenario.accounts)
        engine.process(CreditEvent("X1", 1, "ACC-001", "100.00", AED, 1))
        engine.process(AuthorizationEvent("X2", 1, "ACC-001", "A1", "100.00", AED, 1))
        engine.process(AuthorizationEvent("X3", 1, "ACC-001", "A2", "0.01", AED, 1))
        assertEq(AuthState.APPROVED_ACTIVE, engine.authView("ACC-001", "A1")!!.state)
        assertEq(AuthState.DECLINED, engine.authView("ACC-001", "A2")!!.state)
    }

    r.test("Reversal: double reversal and unknown target are errors and book nothing") {
        val engine = LedgerEngine(Scenario.accounts)
        engine.process(DebitEvent("X1", 1, "ACC-001", "10.00", AED, 1))
        engine.process(ReversalEvent("X2", 1, "ACC-001", "X1", 1))
        engine.process(ReversalEvent("X3", 1, "ACC-001", "X1", 1))
        engine.process(ReversalEvent("X4", 1, "ACC-001", "NOPE", 1))
        assertEq(2, engine.entries().size)
        assertEq(listOf("ALREADY_REVERSED", "REVERSAL_TARGET_NOT_FOUND"), engine.notices().map { it.code })
    }

    r.test("Idempotency: a replayed event id is ignored") {
        val engine = LedgerEngine(Scenario.accounts)
        val e = CreditEvent("X1", 1, "ACC-001", "10.00", AED, 1)
        engine.process(e); engine.process(e)
        assertEq(1, engine.entries().size)
        assertEq("DUPLICATE_EVENT_ID", engine.notices().single().code)
    }

    r.test("Late event E10 is posted on Day 6 with value date 5 and flagged") {
        val engine = Scenario.replay()
        assertTrue(engine.entries().filter { it.sourceEventId == "E10" }.all { it.postingDay == 6 && it.valueDate == 5 }, "posting/value day")
        assertEq("LATE_EVENT", engine.notices().single { it.eventId == "E10" }.code)
    }

    r.test("Split: sums exactly and parts differ by at most one minor unit (exhaustive small cases)") {
        for (minor in 0L..2_000L) for (parts in 1..7) {
            val split = Money.ofMinorUnits(BHD, minor).split(parts)
            assertEq(minor, split.sumOf { it.minorUnits }, "sum $minor/$parts")
            assertTrue(split.maxOf { it.minorUnits } - split.minOf { it.minorUnits } <= 1, "spread $minor/$parts")
        }
    }
}
