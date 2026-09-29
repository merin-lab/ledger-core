package ledger

/**
 * Minimal test harness (~40 lines) so the suite needs nothing but the Kotlin stdlib.
 * Why not JUnit: see REJECTED.md "Approaches abandoned" #1.
 */
class TestCase(val name: String, val knownGap: Boolean, val body: () -> Unit)

class Registry {
    val cases = ArrayList<TestCase>()
    fun test(name: String, body: () -> Unit) { cases += TestCase(name, false, body) }
    /** A test that is EXPECTED to fail: it documents a known gap in this design. */
    fun knownGap(name: String, body: () -> Unit) { cases += TestCase(name, true, body) }
}

fun assertEq(expected: Any?, actual: Any?, what: String = "") {
    if (expected != actual) throw AssertionError("${if (what.isNotEmpty()) "$what: " else ""}expected <$expected> but was <$actual>")
}

fun assertTrue(condition: Boolean, what: String) {
    if (!condition) throw AssertionError(what)
}

inline fun <reified T : Throwable> assertThrows(what: String, block: () -> Unit) {
    try { block() } catch (t: Throwable) {
        if (t is T) return
        throw AssertionError("$what: expected ${T::class.simpleName} but got ${t::class.simpleName}: ${t.message}")
    }
    throw AssertionError("$what: expected ${T::class.simpleName} but nothing was thrown")
}

fun aed(s: String) = Money.of(Currency.AED, s)
fun bhd(s: String) = Money.of(Currency.BHD, s)
