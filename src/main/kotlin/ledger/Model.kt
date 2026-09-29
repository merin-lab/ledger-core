package ledger

/** Business day inside the window. Day 1..6. */
typealias Day = Int

data class Account(val id: String, val currency: Currency, val openingBalance: Money) {
    init { require(openingBalance.currency == currency) }
}

// ---------------------------------------------------------------------------
// Input events: what arrives on the stream. Amounts are strings so that the
// engine, not the caller, decides whether the precision is legal.
// ---------------------------------------------------------------------------
sealed interface InputEvent {
    val id: String
    val day: Day
    val accountId: String
    val valueDate: Day
}

data class CreditEvent(
    override val id: String, override val day: Day, override val accountId: String,
    val amount: String, val currency: Currency, override val valueDate: Day,
    val instalments: Int = 1,
) : InputEvent

data class DebitEvent(
    override val id: String, override val day: Day, override val accountId: String,
    val amount: String, val currency: Currency, override val valueDate: Day,
) : InputEvent

data class AuthorizationEvent(
    override val id: String, override val day: Day, override val accountId: String,
    val authId: String, val amount: String, val currency: Currency, override val valueDate: Day,
) : InputEvent

data class SettlementEvent(
    override val id: String, override val day: Day, override val accountId: String,
    val authId: String, val amount: String, val currency: Currency, override val valueDate: Day,
) : InputEvent

data class ReversalEvent(
    override val id: String, override val day: Day, override val accountId: String,
    val targetEventId: String, override val valueDate: Day,
) : InputEvent

// ---------------------------------------------------------------------------
// Ledger: append-only, immutable records.
// ---------------------------------------------------------------------------
enum class EntryType { CREDIT, DEBIT, SETTLEMENT, REVERSAL, FEE, INTEREST }

data class LedgerEntry(
    val seq: Long,                 // global append order
    val accountId: String,
    val type: EntryType,
    val amount: Money,             // signed: credits +, debits -
    val valueDate: Day,
    val postingDay: Day,           // processing day on which it was appended
    val sourceEventId: String?,    // input event that caused it (null for system entries)
    val reversesSeq: Long? = null, // for REVERSAL entries
    val feeForDay: Day? = null,    // for FEE entries: the value day whose balance triggered it
    val memo: String = "",
)

// ---------------------------------------------------------------------------
// Authorizations are NOT ledger entries (a hold does not move money).
// Their lifecycle is itself an append-only log; state is derived, never stored.
// ---------------------------------------------------------------------------
enum class AuthState { APPROVED_ACTIVE, DECLINED, SETTLED }

sealed interface AuthRecord { val accountId: String; val authId: String; val day: Day; val eventId: String }

data class AuthDecision(
    override val accountId: String, override val authId: String, override val day: Day,
    override val eventId: String, val amount: Money, val approved: Boolean,
    val ledgerAtDecision: Money, val availableBeforeHold: Money,
) : AuthRecord

data class AuthSettled(
    override val accountId: String, override val authId: String, override val day: Day,
    override val eventId: String, val settledAmount: Money, val holdReleased: Money,
) : AuthRecord

data class AuthView(
    val accountId: String, val authId: String, val state: AuthState,
    val holdAmount: Money, val settledAmount: Money?, val decidedOn: Day, val eventId: String,
)

// ---------------------------------------------------------------------------
// Notices: errors and exceptions surfaced per processing day.
// ---------------------------------------------------------------------------
enum class Severity { ERROR, EXCEPTION, WARNING }

data class Notice(
    val day: Day, val severity: Severity, val eventId: String?, val accountId: String?,
    val code: String, val message: String,
)
