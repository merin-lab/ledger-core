package ledger

import ledger.Currency.AED
import ledger.Currency.BHD

/** The brief's accounts and event stream, verbatim, in the brief's replay order. */
object Scenario {
    val accounts = listOf(
        Account("ACC-001", AED, Money.of(AED, "0.00")),
        Account("ACC-002", BHD, Money.of(BHD, "0.000")),
    )

    val events: List<InputEvent> = listOf(
        CreditEvent("E1", 1, "ACC-001", "1,200.00", AED, valueDate = 1),
        DebitEvent("E2", 1, "ACC-001", "950.00", AED, valueDate = 1),
        AuthorizationEvent("E3", 2, "ACC-001", "Auth-A", "200.00", AED, valueDate = 2),
        CreditEvent("E4", 3, "ACC-001", "400.00", AED, valueDate = 3),
        SettlementEvent("E5", 4, "ACC-001", "Auth-A", "185.00", AED, valueDate = 4),
        SettlementEvent("E6", 4, "ACC-001", "Auth-Z", "180.00", AED, valueDate = 4),
        DebitEvent("E7", 5, "ACC-001", "620.00", AED, valueDate = 2),
        AuthorizationEvent("E8", 5, "ACC-001", "Auth-B", "90.00", AED, valueDate = 5),
        ReversalEvent("E9", 6, "ACC-001", targetEventId = "E7", valueDate = 2),
        CreditEvent("E10", 5, "ACC-002", "10.000", BHD, valueDate = 5, instalments = 3),
    )

    /** Replays the full stream (optionally stopping early) and closes the window. */
    fun replay(policy: Policy = Policy(), events: List<InputEvent> = this.events, finish: Boolean = true): LedgerEngine {
        val engine = LedgerEngine(accounts, policy)
        events.forEach(engine::process)
        if (finish) engine.finish()
        return engine
    }
}
