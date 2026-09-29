# AMBIGUITIES

Each entry lists the ambiguity, the options I weighed, what I chose, and whether the choice changes a printed number. Entries marked **(affects output)** change a number in the replay; the rest are guard rails not exercised by the scenario.

## A. Time and ordering

**A1. Three different "days". (affects output)**
Each event has an *event day* (when it happened), a *value date* (when it counts for balances), and there is a *processing day* (when the engine records it).
→ All three are modelled separately. `LedgerEntry.postingDay` is the processing day. Balances filter on `valueDate`, and on `postingDay` when you ask "as known at day N".

**A2. When does a day close? (affects output)**
The brief gives events, not end-of-day markers.
→ Day *d* closes when the first event for a later day arrives, or at the end of the stream. Days with no events are still closed and reported.

**A3. E10 is dated Day 5 but listed after E9 (Day 6). (affects output)**
Options: (a) sort by day; (b) process in list order and treat E10 as late; (c) reject it.
→ (b). The brief says "replayed in this order". E10 is posted on processing Day 6 with value date 5 and flagged `[WARNING] LATE_EVENT`. So the Day 5 report shows ACC-002 at 0.000, which is what was known that evening. The Day 6 value-day view and the final restatement show D5 = 10.000. Sorting (a) was rejected; see REJECTED.md, abandoned approach 7.

**A4. Value dates in the future or outside the window.**
→ Rejected with an error. There is no scheduled-payment feature, and future-dated entries would make "available balance" ill-defined.

## B. Overdraft fee

**B1. Which days are evaluated at each EOD? (affects output)**
Options: (a) only today; (b) every value day ≤ today, using current knowledge.
→ (b). The rule defines closing balance by value date, so a back-valued posting (E7) can make an *earlier* day negative. Option (a) would never charge for D2, even though D2's closing balance is −370.00 by the brief's own definition.

**B2. "Booked with value_date equal to the day assessed": the day evaluated (D2) or the day the check ran (D5)? (affects output)**
→ The day evaluated. The fee for D2's overdraft has value date 2 and posting day 5. Booking it with value date 5 would leave D2 without its own fee and shift the charge onto a different day's balance.

**B3. Do fees count in later days' closing balances? (affects output)**
→ Yes. A fee is a ledger entry, and "all entries with value_date ≤ that day" includes it. D3 is 5.00 rather than 30.00, and D4 is −360.00 rather than −335.00 before its own fee.

**B4. Evaluation order across days. (affects output)**
→ Ascending. With cascading fees (B3), the order changes the result: evaluating D4 before D2's fee is booked would see −335 instead of −360. Only ascending order matches the definition.

**B5. Can a fee make a day negative that was not negative before?**
→ Yes; this follows from B3 and B4. It doesn't happen here: D3 goes from 30.00 to 5.00, which is still ≥ 0.

**B6. Once assessed, is a fee ever re-examined or refunded when the overdraft is cured later (E9)? (affects output)**
→ No. The brief has no refund rule, and an unsolicited refund is a product decision. I believe this is the design's weakest point. It is the deliberately failing test (`KnownGapTest.kt`). See also REJECTED.md, criterion 6.

**B7. The fee is specified in AED. ACC-002 is in BHD.**
Options: convert at some rate; charge AED 25.00 to a BHD account; charge nothing silently; raise an error.
→ Raise `NO_FEE_SCHEDULE` and book nothing. Fees are configured per currency (`Policy.overdraftFee`), and only AED is configured. I won't invent an FX rate. ACC-002 never goes negative, so this isn't triggered in the scenario; the test covers it.

**B8. Is a zero balance "negative"?**
→ No: strict `< 0`.

## C. Interest

**C1. Which balance earns interest: as known each evening, or final value-dated? (affects output)**
→ Final value-dated balances, computed at window close. Point-in-time accrual would give 0.65, versus 0.69 with final balances; see REJECTED.md, abandoned approach 5. This keeps interest consistent with the retroactive fee rule (B1).

**C2. Which days accrue? (affects output)**
→ D1 through D6 inclusive, six accruals per account. Day 6 accrues on its closing balance *before* the capitalization credit, which is value-dated Day 6.

**C3. Does the interest base include fees? (affects output)**
→ Yes. "Closing ledger balance" includes every entry, fees too.

**C4. Order at EOD 6.**
→ Fees first, then interest on the post-fee balance, then capitalization. The D6 fee test ignores the capitalization credit, because interest is derived from that closing balance and cannot also feed back into it. Edge case: an account slightly negative on D6 with positive interest earned earlier would still be charged. That doesn't happen here.

**C5. What does "0.04% per day" mean?**
→ Simple daily rate 0.0004 on each day's balance, not compounded within the window (accruals capitalize only at the end), and not an annual rate divided by 365.

**C6. Negative balances.**
→ No accrual. The brief says "positive balances only". No debit interest is charged; overdrafts are priced only by the fee.

**C7. Rounding mode for each accrual.**
→ `HALF_EVEN`. None of this scenario's accruals is an exact tie (0.094 and 0.084 are not midpoints), so HALF_UP would give the same 0.69. See NUMBERS.md.

**C8. What if total interest is zero?**
→ No entry is booked; zero-amount journal entries are noise. The report still prints the accrual schedule.

## D. Authorizations and settlements

**D1. Which ledger balance feeds "available"? (affects output)**
→ All entries with value date ≤ the processing day, as known at that moment, minus active holds.

**D2. Is an authorization re-judged when back-valued postings arrive? (affects output)**
→ No; approval is a point-in-time decision. Auth-A was approved on Day 2 (available 50.00). With E7 known it would have been declined, but E7 didn't exist yet.

**D3. E7 and E8 are both Day 5; does E7 count when E8 is decided? (affects output)**
→ Yes: stream order. Auth-B is declined (available −335.00 before the hold).

**D4. "At or above zero" boundary.**
→ An authorization that leaves available at exactly 0.00 is approved. Tested.

**D5. Partial settlement (185.00 against a 200.00 hold). (affects output)**
Options: (a) release the whole hold, single capture; (b) keep 15.00 held for further captures.
→ (a). There is no multi-capture signal in the brief. Auth-A goes to SETTLED and the full 200.00 hold is released.

**D6. Settlement above the hold.**
→ Booked, with `[WARNING] OVER_SETTLEMENT` (tips, fuel pumps). Not in the scenario.

**D7. Settlement with no auth, a declined auth, or an already-settled auth. (affects output)**
→ Force-post plus `[EXCEPTION]` by default; REJECT is available as a policy switch. See REJECTED.md, criterion 4.

**D8. Hold expiry.**
→ None inside the window. The brief gives no expiry period, and I didn't want to invent a constant. A real system would release an unsettled hold after N days.

**D9. Is an authorization decline an error?**
→ No. It is a normal outcome, reported under the authorization state and also as a `WARNING` so it shows in the per-day list. `ERROR` is reserved for events the engine refused to book.

**D10. Duplicate authorization ID on one account.**
→ Rejected with `DUPLICATE_AUTH_ID`.

## E. Postings, reversals, precision

**E1. Are booked debits balance-checked? (affects output)**
→ No. The brief gates only *authorizations* on available balance. A booked debit such as E7 records something that has already happened. It can overdraw the account, and that is what the fee is for.

**E2. What does a reversal reverse? (affects output)**
→ Every journal entry produced by the target event, each negated, in full. Partial reversals are not supported. Reversing a reversal, a fee, or interest is refused. So is a second reversal of the same event (`ALREADY_REVERSED`).

**E3. Reversal value date. (affects output)**
→ The reversal event's own value date (Day 2), which matches E7. A mismatch is allowed but raises `[WARNING] REVERSAL_VALUE_DATE_DIFFERS`.

**E4. Which instalment gets the leftover minor unit? (affects output)**
→ The first: 3.334, 3.333, 3.333. Front-loading is the usual convention and makes the order deterministic. The alternative (last instalment absorbs it) is equally defensible; only the order changes.

**E5. One entry or three for E10? (affects output)**
→ Three journal entries, one per instalment, all with value date 5 and all traceable to `E10`. One entry of 10.000 would ignore "posted as three … instalments".

**E6. Input precision greater than the currency allows (e.g. AED 1.005).**
→ Rejected (`INVALID_AMOUNT`), never rounded. Silent rounding of *inputs* hides upstream bugs. Trailing zeros are fine ("10.0000" BHD is accepted as 10.000).

**E7. "1,200.00": thousands separators.**
→ Accepted by the parser and stripped.

**E8. Currency on the event differs from the account's currency.**
→ Rejected (`INVALID_AMOUNT`). No implicit FX.

**E9. Opening balances.**
→ The seed of the balance sum, not a journal entry. Both are zero.

**E10. Replaying the same event ID twice.**
→ Idempotent: the second copy is ignored with `DUPLICATE_EVENT_ID`.

## F. Output

**F1. "Closing ledger balance per day": as printed that evening, or restated? (affects output)**
→ Both. Each day block prints the balance *as known at that EOD* plus a "value-day view (now)" line. A final restatement block prints every day with everything known at window close. For example, Day 5 printed −410.00 that evening, but its final restated balance is 210.00 after E9.

**F2. What counts as an "error" in the per-day output?**
→ Three severities: `ERROR` (refused, nothing booked), `EXCEPTION` (booked, needs human review) and `WARNING` (booked or decided normally, but notable).
