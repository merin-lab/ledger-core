package ledger

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * ISO 4217 minor-unit precision. AED = 2 (fils), BHD = 3 (fils; 1 BHD = 1000 fils).
 * Precision is a property of the currency, never of an individual amount.
 */
enum class Currency(val precision: Int) {
    AED(2),
    BHD(3);
}

/**
 * An amount that is ALWAYS held at exactly its currency's precision.
 * Construction fails rather than silently rounding: rounding is a business decision
 * and must happen explicitly at the call site (see [Money.ofRounded]).
 */
class Money private constructor(val currency: Currency, val amount: BigDecimal) : Comparable<Money> {

    init {
        require(amount.scale() == currency.precision) {
            "Money $amount has scale ${amount.scale()}, $currency requires ${currency.precision}"
        }
    }

    companion object {
        /** Exact parse. Rejects inputs carrying more precision than the currency allows. */
        fun of(currency: Currency, text: String): Money {
            val raw = BigDecimal(text.replace(",", ""))
            val stripped = raw.stripTrailingZeros()
            require(stripped.scale() <= currency.precision) {
                "$text has more decimal places than $currency allows (${currency.precision})"
            }
            return Money(currency, raw.setScale(currency.precision, RoundingMode.UNNECESSARY))
        }

        fun zero(currency: Currency) = Money(currency, BigDecimal.ZERO.setScale(currency.precision))

        /** The ONLY place an arbitrary-precision value becomes Money. Caller chooses the rounding. */
        fun ofRounded(currency: Currency, value: BigDecimal, mode: RoundingMode): Money =
            Money(currency, value.setScale(currency.precision, mode))

        fun ofMinorUnits(currency: Currency, minor: Long): Money =
            Money(currency, BigDecimal.valueOf(minor, currency.precision))
    }

    val minorUnits: Long get() = amount.unscaledValue().longValueExact()

    operator fun plus(o: Money): Money { same(o); return Money(currency, amount + o.amount) }
    operator fun minus(o: Money): Money { same(o); return Money(currency, amount - o.amount) }
    operator fun unaryMinus(): Money = Money(currency, amount.negate())

    fun isNegative() = amount.signum() < 0
    fun isPositive() = amount.signum() > 0
    fun isZero() = amount.signum() == 0

    /**
     * Splits into [parts] instalments that sum EXACTLY to this amount.
     * Works in minor units: the remainder r (< parts) is spread one minor unit each
     * over the first r instalments. 10.000 BHD / 3 -> 3.334, 3.333, 3.333.
     * No instalment differs from another by more than one minor unit.
     */
    fun split(parts: Int): List<Money> {
        require(parts > 0) { "parts must be > 0" }
        require(!isNegative()) { "split is defined for non-negative amounts only" }
        val total = minorUnits
        val base = total / parts
        val remainder = total % parts
        return (0 until parts).map { i -> ofMinorUnits(currency, base + if (i < remainder) 1 else 0) }
    }

    private fun same(o: Money) =
        require(currency == o.currency) { "Currency mismatch: $currency vs ${o.currency}" }

    override fun compareTo(other: Money): Int { same(other); return amount.compareTo(other.amount) }
    override fun equals(other: Any?) = other is Money && other.currency == currency && other.amount == amount
    override fun hashCode() = 31 * currency.hashCode() + amount.hashCode()
    override fun toString() = "$currency ${amount.toPlainString()}"
    fun plain(): String = amount.toPlainString()
}

fun List<Money>.sum(currency: Currency): Money = fold(Money.zero(currency)) { a, b -> a + b }
