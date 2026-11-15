package com.ctm.credit.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Local copy of the server calculator (calculator version "v1").
 * Must give exactly the same result as the SQL functions compute_offer() and installment_plan().
 * Only BigDecimal is used: never Double.
 */
object LoanCalculator {
    const val VERSION = "v1"

    private val THIRTY = BigDecimal(30)
    private val HUNDRED = BigDecimal(100)

    // Intermediate divisions are truncated far below one cent, then rounded once.
    // Truncation can never cross a rounding tie, so the result equals a single exact rounding.
    private const val WORK_SCALE = 30

    /** Rounding used by the server: half away from zero, 2 decimals. */
    fun money(value: BigDecimal): BigDecimal = value.setScale(2, RoundingMode.HALF_UP)

    /** Exact percentage of an amount, rounded like the server: round(amount * pct / 100, 2). */
    fun percentOf(amount: BigDecimal, pct: BigDecimal): BigDecimal =
        money(amount.multiply(pct).divide(HUNDRED, WORK_SCALE, RoundingMode.DOWN))

    fun installmentCount(durationDays: Int, frequency: Frequency): Int =
        (durationDays + frequency.periodDays - 1) / frequency.periodDays

    fun compute(input: OfferInput): Offer {
        require(input.durationDays > 0) { "duration must be positive" }
        require(input.retailPrice.signum() >= 0 && input.downPayment.signum() >= 0) { "amounts cannot be negative" }
        require(hasAtMostTwoDecimals(input.retailPrice) && hasAtMostTwoDecimals(input.downPayment)) {
            "amounts must have at most 2 decimals"
        }

        val fee = money(input.retailPrice.multiply(input.processingFeeRate))
        val financed = money(input.retailPrice.add(fee).subtract(input.downPayment))
        val interest = money(
            financed
                .multiply(input.interestRatePer30Days)
                .multiply(BigDecimal(input.durationDays))
                .divide(THIRTY, WORK_SCALE, RoundingMode.DOWN),
        )
        val total = financed.add(interest)

        val count = installmentCount(input.durationDays, input.frequency)
        val base = money(total.divide(BigDecimal(count), WORK_SCALE, RoundingMode.DOWN))
        val last = total.subtract(base.multiply(BigDecimal(count - 1)))

        val schedule = (1..count).map { n ->
            Installment(
                number = n,
                dueDate = if (input.frequency == Frequency.MONTHLY) {
                    // n calendar months counted from the start date each time (not month after month).
                    input.startDate.plusMonths(n.toLong())
                } else {
                    input.startDate.plusDays(n.toLong() * input.frequency.periodDays)
                },
                amountDue = if (n == count) last else base,
            )
        }

        return Offer(
            processingFee = fee,
            financedAmount = financed,
            interestAmount = interest,
            totalDue = total,
            installmentCount = count,
            installmentAmount = base,
            lastInstallmentAmount = last,
            firstDueDate = schedule.first().dueDate,
            endDate = schedule.last().dueDate,
            schedule = schedule,
        )
    }

    private fun hasAtMostTwoDecimals(v: BigDecimal): Boolean = v.stripTrailingZeros().scale() <= 2
}
