package ledger

/**
 * ONE TEST THAT FAILS AGAINST THIS DESIGN, ON PURPOSE.
 *
 * It asserts what a customer (and most regulators) would reasonably expect, and which this
 * engine does NOT do. It is left red so the gap is visible in every run instead of being
 * buried in a doc. Run `scripts/run.sh suite --skip-known-gap` for a green build.
 */
internal fun knownGapTests(r: Registry) {
    r.knownGap("GAP a reversed erroneous posting should leave the customer whole") {
        val engine = Scenario.replay()

        // E7 (620.00, back-valued to Day 2) arrives on Day 5. EOD 5 walks value days 1..5 with
        // what it knows NOW and books three fees (D2, D4, D5), each correct at the moment it
        // was assessed.
        //
        // E9 on Day 6 then says E7 should never have happened. The rule "fee when closing is
        // negative" is re-evaluated at EOD 6 and is no longer true for D2/D4/D5, but the design
        // only ever ADDS fees; it never asks "are my earlier fees still justified?".
        //
        // WHAT THIS REVEALS:
        //  1. Append-only was implemented as "never undo", but append-only only forbids
        //     MUTATION. A compensating FEE_REFUND entry would be perfectly append-only.
        //     I conflated the storage rule with a business rule.
        //  2. Fee assessment is event-sourced on the way IN (back-valued debit -> new fees)
        //     but not on the way OUT (back-valued reversal -> no refunds). The asymmetry is the bug.
        //  3. The same root cause declined Auth-B (E8): E7 pushed available to -335.00. A
        //     decline cannot be un-declined after the fact, which is exactly why fees, the
        //     part that CAN be compensated, arguably should be.
        //
        // WHY IT IS NOT FIXED: the brief's fee rule says when to charge, not when to refund. Auto-
        // refunding is a product/policy decision (refund all? only fees whose trigger vanished?
        // what if the day is negative for an unrelated reason?). Also refunding the D2 fee
        // raises D4, which could itself flip another day's sign, so the fix needs a fixed-point
        // recompute, not a one-liner. Criterion 6 asks for something stronger still (see REJECTED.md).
        val fees = engine.entries().filter { it.accountId == "ACC-001" && it.type == EntryType.FEE }
        val netFees = fees.map { it.amount }.sum(Currency.AED)

        // Observed: AED -75.00 (3 x 25.00 still charged). Expected by the customer: 0.00.
        assertEq(aed("0.00"), netFees, "net overdraft fees on ACC-001 after E7 was reversed")
    }
}
