package ledger

/**
 * Replays the brief's event stream and prints the per-day report.
 *   --reject-unmatched   use UnmatchedSettlementPolicy.REJECT instead of the default force-post
 */
fun main(args: Array<String>) {
    val policy = if ("--reject-unmatched" in args)
        Policy(unmatchedSettlement = UnmatchedSettlementPolicy.REJECT) else Policy()
    println("ledger-core replay - unmatched settlement policy: ${policy.unmatchedSettlement}")
    println()
    print(Report.render(Scenario.replay(policy)))
}
