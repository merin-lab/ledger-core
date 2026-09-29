package ledger

/** One overdraft fee decision made during an end-of-day run. */
data class FeeAssessment(val accountId: String, val forValueDay: Day, val balanceBeforeFee: Money, val entry: LedgerEntry)

data class InterestAccrual(val valueDay: Day, val closingBalance: Money, val accrual: Money)

data class InterestCapitalization(
    val accountId: String,
    val accruals: List<InterestAccrual>,
    val total: Money,
    val entry: LedgerEntry?,          // null when total is zero: no zero-amount entries are booked
)

data class AccountDaySnapshot(
    val account: Account,
    val closingLedger: Money,                 // all entries with valueDate <= day, as known at this EOD
    val activeHolds: Money,
    val available: Money,
    val valueDayView: Map<Day, Money>,        // every value day 1..day restated with current knowledge
    val feesAssessed: List<FeeAssessment>,    // fees appended during THIS EOD run (any value day)
    val authorizations: List<AuthView>,       // all auths on the account as of this EOD
    val capitalization: InterestCapitalization?,
)

data class DayReport(
    val day: Day,
    val eventsProcessed: List<String>,
    val accounts: List<AccountDaySnapshot>,
    val notices: List<Notice>,
)

/**
 * In-memory, single-threaded, append-only ledger core.
 *
 * Time model: the engine has a processing day ("today"). Events carry their own day;
 * an event for a later day first closes every intervening day (end-of-day run).
 * An event for an EARLIER day than today is late: it is still processed, stamped with
 * today's posting day, and flagged. Value dates are independent of both.
 */
class LedgerEngine(accounts: List<Account>, val policy: Policy = Policy()) {

    private val accounts: Map<String, Account> = accounts.associateBy { it.id }
    private val entries = ArrayList<LedgerEntry>()          // append-only
    private val authLog = ArrayList<AuthRecord>()           // append-only
    private val notices = ArrayList<Notice>()               // append-only
    private val reports = ArrayList<DayReport>()            // append-only
    private val seenEventIds = HashSet<String>()
    private val eventsToday = ArrayList<String>()
    private var nextSeq = 1L

    var today: Day = policy.windowFirstDay
        private set
    private var closedThrough: Day = policy.windowFirstDay - 1

    // ---- read-only views (copies: callers can never mutate engine state) ----
    fun entries(): List<LedgerEntry> = entries.toList()
    fun notices(): List<Notice> = notices.toList()
    fun reports(): List<DayReport> = reports.toList()
    fun account(id: String): Account = accounts[id] ?: error("unknown account $id")

    // =========================================================================
    // Event intake
    // =========================================================================
    fun process(event: InputEvent) {
        check(closedThrough < policy.windowLastDay) { "window is closed" }
        if (event.day > today) advanceTo(event.day)
        eventsToday += event.id

        if (!seenEventIds.add(event.id)) {
            return notice(Severity.ERROR, event, "DUPLICATE_EVENT_ID", "event id already processed; ignored (idempotency)")
        }
        val account = accounts[event.accountId]
            ?: return notice(Severity.ERROR, event, "UNKNOWN_ACCOUNT", "no such account")
        if (event.day < today) {
            notice(Severity.WARNING, event, "LATE_EVENT",
                "event dated day ${event.day} arrived after day ${event.day} was closed; posted on day $today with value date ${event.valueDate}")
        }
        if (event.valueDate < policy.windowFirstDay || event.valueDate > policy.windowLastDay) {
            return notice(Severity.ERROR, event, "VALUE_DATE_OUT_OF_WINDOW", "value date ${event.valueDate} outside window")
        }
        if (event.valueDate > today) {
            return notice(Severity.ERROR, event, "FUTURE_VALUE_DATE", "value date ${event.valueDate} is after processing day $today")
        }
        try {
            when (event) {
                is CreditEvent -> credit(account, event)
                is DebitEvent -> debit(account, event)
                is AuthorizationEvent -> authorize(account, event)
                is SettlementEvent -> settle(account, event)
                is ReversalEvent -> reverse(account, event)
            }
        } catch (e: IllegalArgumentException) {
            notice(Severity.ERROR, event, "INVALID_AMOUNT", e.message ?: "invalid amount")
        }
    }

    /** Closes every remaining day of the window (capitalizing interest on the last). */
    fun finish() {
        if (closedThrough < policy.windowLastDay) advanceTo(policy.windowLastDay + 1)
    }

    private fun advanceTo(day: Day) {
        while (today < day && today <= policy.windowLastDay) {
            endOfDay(today)
            today += 1
        }
    }

    // ---- handlers -----------------------------------------------------------
    private fun money(account: Account, event: InputEvent, currency: Currency, text: String): Money {
        require(currency == account.currency) { "event currency $currency does not match account currency ${account.currency}" }
        val m = Money.of(currency, text)
        require(m.isPositive()) { "amount must be positive, was $text" }
        return m
    }

    private fun credit(account: Account, e: CreditEvent) {
        val total = money(account, e, e.currency, e.amount)
        require(e.instalments >= 1) { "instalments must be >= 1" }
        val parts = total.split(e.instalments)
        parts.forEachIndexed { i, part ->
            append(account, EntryType.CREDIT, part, e.valueDate, e.id,
                memo = if (parts.size > 1) "instalment ${i + 1}/${parts.size}" else "")
        }
    }

    private fun debit(account: Account, e: DebitEvent) {
        // Booked debits are not balance-checked: they are instructions that have already happened.
        // Only authorizations are gated by available balance (brief, rule 5).
        append(account, EntryType.DEBIT, -money(account, e, e.currency, e.amount), e.valueDate, e.id)
    }

    private fun authorize(account: Account, e: AuthorizationEvent) {
        val amount = money(account, e, e.currency, e.amount)
        if (authLog.any { it is AuthDecision && it.accountId == account.id && it.authId == e.authId }) {
            return notice(Severity.ERROR, e, "DUPLICATE_AUTH_ID", "${e.authId} already exists on ${account.id}")
        }
        val ledger = balance(account.id, asOfValueDay = today)
        val availableBefore = ledger - activeHolds(account.id)
        val approved = !(availableBefore - amount).isNegative()
        authLog += AuthDecision(account.id, e.authId, today, e.id, amount, approved, ledger, availableBefore)
        if (!approved) {
            notice(Severity.WARNING, e, "AUTH_DECLINED",
                "${e.authId} for $amount declined: available $availableBefore - hold would be ${availableBefore - amount}")
        }
    }

    private fun settle(account: Account, e: SettlementEvent) {
        val amount = money(account, e, e.currency, e.amount)
        val auth = authView(account.id, e.authId)
        if (auth != null && auth.state == AuthState.APPROVED_ACTIVE) {
            append(account, EntryType.SETTLEMENT, -amount, e.valueDate, e.id, memo = "settles ${e.authId}")
            authLog += AuthSettled(account.id, e.authId, today, e.id, amount, auth.holdAmount)
            if (amount > auth.holdAmount) {
                notice(Severity.WARNING, e, "OVER_SETTLEMENT", "${e.authId} settled $amount above hold ${auth.holdAmount}")
            }
            return
        }
        val why = when (auth?.state) {
            null -> "no authorization ${e.authId} exists on ${account.id}"
            AuthState.DECLINED -> "${e.authId} was declined"
            AuthState.SETTLED -> "${e.authId} is already settled"
            AuthState.APPROVED_ACTIVE -> error("unreachable")
        }
        when (policy.unmatchedSettlement) {
            UnmatchedSettlementPolicy.FORCE_POST_AND_FLAG -> {
                append(account, EntryType.SETTLEMENT, -amount, e.valueDate, e.id, memo = "FORCE-POST unmatched ${e.authId}")
                notice(Severity.EXCEPTION, e, "UNMATCHED_SETTLEMENT", "$why; force-posted $amount and queued for dispute/review")
            }
            UnmatchedSettlementPolicy.REJECT ->
                notice(Severity.ERROR, e, "UNMATCHED_SETTLEMENT_REJECTED", "$why; not booked")
        }
    }

    private fun reverse(account: Account, e: ReversalEvent) {
        val targets = entries.filter { it.sourceEventId == e.targetEventId && it.accountId == account.id }
        if (targets.isEmpty()) {
            return notice(Severity.ERROR, e, "REVERSAL_TARGET_NOT_FOUND", "${e.targetEventId} has no postings on ${account.id}")
        }
        if (targets.any { it.type == EntryType.REVERSAL || it.type == EntryType.FEE || it.type == EntryType.INTEREST }) {
            return notice(Severity.ERROR, e, "REVERSAL_TARGET_NOT_REVERSIBLE", "${e.targetEventId} is not a customer posting")
        }
        val alreadyReversed = targets.filter { t -> entries.any { it.reversesSeq == t.seq } }
        if (alreadyReversed.isNotEmpty()) {
            return notice(Severity.ERROR, e, "ALREADY_REVERSED", "${e.targetEventId} has already been reversed")
        }
        targets.forEach { t ->
            if (t.valueDate != e.valueDate) {
                notice(Severity.WARNING, e, "REVERSAL_VALUE_DATE_DIFFERS",
                    "original value date ${t.valueDate}, reversal value date ${e.valueDate}")
            }
            append(account, EntryType.REVERSAL, -t.amount, e.valueDate, e.id, reversesSeq = t.seq, memo = "reverses ${e.targetEventId}")
        }
    }

    // =========================================================================
    // End of day
    // =========================================================================
    private fun endOfDay(day: Day) {
        val snapshots = accounts.values.map { account ->
            val fees = assessOverdraftFees(account, day)
            val capitalization = if (day == policy.windowLastDay) capitalizeInterest(account, day) else null
            val closing = balance(account.id, day)
            val holds = activeHolds(account.id)
            AccountDaySnapshot(
                account = account,
                closingLedger = closing,
                activeHolds = holds,
                available = closing - holds,
                valueDayView = (policy.windowFirstDay..day).associateWith { balance(account.id, it) },
                feesAssessed = fees,
                authorizations = authViews(account.id),
                capitalization = capitalization,
            )
        }
        reports += DayReport(day, eventsToday.toList(), snapshots, notices.filter { it.day == day })
        eventsToday.clear()
        closedThrough = day
    }

    /**
     * Walks EVERY value day up to [day] in ascending order with current knowledge, so a
     * back-valued posting that arrived today can trigger fees on earlier days. Ascending order
     * matters: a fee booked for day d is part of every later day's closing balance.
     * At most one fee per (account, value day), ever: an existing fee is never re-assessed,
     * and never removed (append-only), even if later postings cure the overdraft.
     */
    private fun assessOverdraftFees(account: Account, day: Day): List<FeeAssessment> {
        val out = ArrayList<FeeAssessment>()
        for (d in policy.windowFirstDay..day) {
            val alreadyCharged = entries.any { it.accountId == account.id && it.type == EntryType.FEE && it.feeForDay == d }
            if (alreadyCharged) continue
            val bal = balance(account.id, d)
            if (!bal.isNegative()) continue
            val fee = policy.feeFor(account.currency)
            if (fee == null) {
                notices += Notice(today, Severity.ERROR, null, account.id, "NO_FEE_SCHEDULE",
                    "value day $d closed at $bal but no overdraft fee is configured for ${account.currency}")
                continue
            }
            val entry = append(account, EntryType.FEE, -fee, d, null, feeForDay = d,
                memo = "overdraft fee for value day $d (closing $bal)")
            out += FeeAssessment(account.id, d, bal, entry)
        }
        return out
    }

    /**
     * Accrues on each value day's FINAL closing balance (as known at the end of the window,
     * after fees), positive balances only. Each daily accrual is rounded to currency precision;
     * the capitalized total is DEFINED as the sum of those rounded accruals, so they reconcile
     * exactly by construction and there is never a remainder to discard.
     */
    fun interestSchedule(accountId: String, throughDay: Day): InterestCapitalization {
        val account = account(accountId)
        val accruals = (policy.windowFirstDay..throughDay).map { d ->
            val bal = balance(accountId, d, exclude = setOf(EntryType.INTEREST))
            val accrual = if (bal.isPositive())
                Money.ofRounded(account.currency, bal.amount * policy.dailyInterestRate, policy.interestRounding)
            else Money.zero(account.currency)
            InterestAccrual(d, bal, accrual)
        }
        return InterestCapitalization(accountId, accruals, accruals.map { it.accrual }.sum(account.currency), null)
    }

    private fun capitalizeInterest(account: Account, day: Day): InterestCapitalization {
        val schedule = interestSchedule(account.id, day)
        if (schedule.total.isZero()) return schedule
        val entry = append(account, EntryType.INTEREST, schedule.total, day, null,
            memo = "interest capitalization, ${schedule.accruals.size} daily accruals")
        return schedule.copy(entry = entry)
    }

    // =========================================================================
    // Queries
    // =========================================================================
    /**
     * Ledger balance for [accountId] at close of [asOfValueDay]: opening balance plus every entry
     * with valueDate <= asOfValueDay and postingDay <= [knownAt]. Holds are NOT ledger.
     */
    fun balance(
        accountId: String, asOfValueDay: Day, knownAt: Day = Int.MAX_VALUE,
        exclude: Set<EntryType> = emptySet(),
    ): Money {
        val account = account(accountId)
        return entries.asSequence()
            .filter { it.accountId == accountId && it.valueDate <= asOfValueDay && it.postingDay <= knownAt && it.type !in exclude }
            .fold(account.openingBalance) { acc, e -> acc + e.amount }
    }

    fun activeHolds(accountId: String): Money =
        authViews(accountId).filter { it.state == AuthState.APPROVED_ACTIVE }.map { it.holdAmount }.sum(account(accountId).currency)

    fun available(accountId: String): Money = balance(accountId, today) - activeHolds(accountId)

    fun authView(accountId: String, authId: String): AuthView? = authViews(accountId).firstOrNull { it.authId == authId }

    fun authViews(accountId: String): List<AuthView> =
        authLog.filterIsInstance<AuthDecision>().filter { it.accountId == accountId }.map { d ->
            val settled = authLog.filterIsInstance<AuthSettled>().firstOrNull { it.accountId == accountId && it.authId == d.authId }
            val state = when {
                !d.approved -> AuthState.DECLINED
                settled != null -> AuthState.SETTLED
                else -> AuthState.APPROVED_ACTIVE
            }
            AuthView(accountId, d.authId, state, d.amount, settled?.settledAmount, d.day, d.eventId)
        }

    // ---- internals ------------------------------------------------------------
    private fun append(
        account: Account, type: EntryType, amount: Money, valueDate: Day, sourceEventId: String?,
        reversesSeq: Long? = null, feeForDay: Day? = null, memo: String = "",
    ): LedgerEntry {
        check(amount.currency == account.currency)
        val e = LedgerEntry(nextSeq++, account.id, type, amount, valueDate, today, sourceEventId, reversesSeq, feeForDay, memo)
        entries += e
        return e
    }

    private fun notice(severity: Severity, e: InputEvent, code: String, message: String) {
        notices += Notice(today, severity, e.id, e.accountId, code, message)
    }
}
