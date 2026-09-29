package ledger

import java.math.BigDecimal
import java.math.RoundingMode

/** What to do with a settlement whose authorization ID has no active approved hold. */
enum class UnmatchedSettlementPolicy {
    /** Book the debit (money has already moved at scheme level) and raise an EXCEPTION for ops. Default. */
    FORCE_POST_AND_FLAG,
    /** Refuse to book. Only correct if the ledger is upstream of the card scheme, which it is not here. */
    REJECT,
}

/**
 * Every constant the engine uses. Nothing numeric is hard-coded elsewhere.
 * See NUMBERS.md for why each value is what it is.
 */
data class Policy(
    val windowFirstDay: Day = 1,
    val windowLastDay: Day = 6,
    /** Overdraft fee per currency. Only AED is specified by the brief; see AMBIGUITIES.md #fee-currency. */
    val overdraftFee: Map<Currency, String> = mapOf(Currency.AED to "25.00"),
    /** 0.04% per day, expressed as a plain decimal fraction. */
    val dailyInterestRate: BigDecimal = BigDecimal("0.0004"),
    val interestRounding: RoundingMode = RoundingMode.HALF_EVEN,
    val unmatchedSettlement: UnmatchedSettlementPolicy = UnmatchedSettlementPolicy.FORCE_POST_AND_FLAG,
) {
    fun feeFor(currency: Currency): Money? = overdraftFee[currency]?.let { Money.of(currency, it) }
}
