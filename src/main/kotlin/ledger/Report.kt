package ledger

object Report {
    fun render(engine: LedgerEngine): String = buildString {
        engine.reports().forEach { r -> appendDay(r) }
        appendRestatement(engine)
        appendJournal(engine)
    }

    private fun StringBuilder.appendDay(r: DayReport) {
        appendLine("=".repeat(78))
        appendLine("DAY ${r.day} - end of day    events processed: ${r.eventsProcessed.ifEmpty { listOf("none") }.joinToString(", ")}")
        appendLine("=".repeat(78))
        r.accounts.forEach { s ->
            val c = s.account.currency
            appendLine("${s.account.id} [$c]")
            appendLine("  closing ledger balance : ${s.closingLedger.plain()}")
            appendLine("  active holds           : ${s.activeHolds.plain()}   available: ${s.available.plain()}")
            appendLine("  value-day view (now)   : " + s.valueDayView.entries.joinToString("  ") { "D${it.key} ${it.value.plain()}" })
            if (s.feesAssessed.isEmpty()) appendLine("  fee assessments        : none")
            else {
                appendLine("  fee assessments        :")
                s.feesAssessed.forEach {
                    appendLine("    - ${c} ${(-it.entry.amount).plain()} for value day ${it.forValueDay} (closing before fee ${it.balanceBeforeFee.plain()})")
                }
            }
            if (s.authorizations.isEmpty()) appendLine("  authorizations         : none")
            else {
                appendLine("  authorizations         :")
                s.authorizations.forEach { a ->
                    val detail = when (a.state) {
                        AuthState.APPROVED_ACTIVE -> "hold ${a.holdAmount.plain()} active"
                        AuthState.DECLINED -> "requested ${a.holdAmount.plain()}, no hold"
                        AuthState.SETTLED -> "hold ${a.holdAmount.plain()} released, settled ${a.settledAmount!!.plain()}"
                    }
                    appendLine("    - ${a.authId}: ${a.state} ($detail; decided day ${a.decidedOn} by ${a.eventId})")
                }
            }
            s.capitalization?.let { cap ->
                appendLine("  interest (0.04%/day, positive closing balances, rounded per day):")
                cap.accruals.forEach { appendLine("    D${it.valueDay}: on ${it.closingBalance.plain()} -> ${it.accrual.plain()}") }
                appendLine("    capitalized: ${cap.total.plain()} (= sum of rounded daily accruals)" +
                    if (cap.entry == null) " - nothing booked" else " booked value date ${cap.entry.valueDate}")
            }
        }
        if (r.notices.isEmpty()) appendLine("errors / exceptions: none")
        else {
            appendLine("errors / exceptions:")
            r.notices.forEach { n -> appendLine("  [${n.severity}] ${n.code} ${n.eventId ?: "-"} ${n.accountId ?: ""}: ${n.message}") }
        }
        appendLine()
    }

    private fun StringBuilder.appendRestatement(engine: LedgerEngine) {
        appendLine("=".repeat(78))
        appendLine("FINAL RESTATEMENT - each value day's closing balance with everything known at window close")
        appendLine("=".repeat(78))
        engine.reports().last().accounts.forEach { s ->
            appendLine("${s.account.id}: " + s.valueDayView.entries.joinToString("  ") { "D${it.key} ${it.value.plain()}" })
        }
        appendLine()
    }

    private fun StringBuilder.appendJournal(engine: LedgerEngine) {
        appendLine("=".repeat(78))
        appendLine("JOURNAL (append-only, in posting order)")
        appendLine("=".repeat(78))
        appendLine(String.format("%-4s %-8s %-10s %12s %4s %6s %-5s %s", "seq", "account", "type", "amount", "vd", "posted", "src", "memo"))
        engine.entries().forEach { e ->
            appendLine(String.format("%-4d %-8s %-10s %12s %4s %6s %-5s %s",
                e.seq, e.accountId, e.type, e.amount.plain(), "D${e.valueDate}", "D${e.postingDay}", e.sourceEventId ?: "sys", e.memo))
        }
    }
}
