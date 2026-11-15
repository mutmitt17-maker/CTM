package com.ctm.credit.model

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LoanSimulatorTest {
    private fun bd(s: String) = BigDecimal(s)

    private val terms = CreditTerms(
        downPaymentMinPct = bd("20"),
        downPaymentMaxPct = bd("40"),
        allowedDurations = listOf(30, 60, 90, 120, 180),
        processingFeeRate = bd("0.03"),
        interestRatePer30Days = bd("0.06"),
        penaltyRatePerDay = bd("0.005"),
        graceDays = 3,
        lockAfterDaysOverdue = 7,
    )
    private val item = CatalogueItem("i1", "Tecno", "Spark 10", "noir", bd("100.00"), null, "7518")
    private val start = LocalDate.of(2026, 1, 1)

    @Test
    fun `refuses a down payment under the minimum`() {
        val r = LoanSimulator.simulate(item, terms, bd("19.99"), 90, Frequency.WEEKLY, start)
        assertIs<SimulationResult.Refused>(r)
        assertEquals(SimulationRefusal.DOWN_PAYMENT_BELOW_MINIMUM, r.reason)
        assertEquals(bd("20.00"), r.minimumDownPayment)
    }

    @Test
    fun `item minimum overrides the policy minimum`() {
        val strict = item.copy(minDownPaymentPct = bd("35"))
        val r = LoanSimulator.simulate(strict, terms, bd("30"), 90, Frequency.WEEKLY, start)
        assertIs<SimulationResult.Refused>(r)
        assertEquals(bd("35.00"), r.minimumDownPayment)
    }

    @Test
    fun `refuses a duration that is not offered`() {
        val r = LoanSimulator.simulate(item, terms, bd("30"), 45, Frequency.WEEKLY, start)
        assertIs<SimulationResult.Refused>(r)
        assertEquals(SimulationRefusal.DURATION_NOT_OFFERED, r.reason)
    }

    @Test
    fun `refuses a down payment above the price`() {
        val r = LoanSimulator.simulate(item, terms, bd("100.01"), 90, Frequency.WEEKLY, start)
        assertIs<SimulationResult.Refused>(r)
        assertEquals(SimulationRefusal.DOWN_PAYMENT_ABOVE_PRICE, r.reason)
    }

    @Test
    fun `warns above the usual range but still computes`() {
        val r = LoanSimulator.simulate(item, terms, bd("50"), 90, Frequency.WEEKLY, start)
        assertIs<SimulationResult.Success>(r)
        assertEquals(listOf(SimulationWarning.DOWN_PAYMENT_ABOVE_USUAL_RANGE), r.warnings)
    }

    @Test
    fun `exactly the minimum is accepted without warning`() {
        val r = LoanSimulator.simulate(item, terms, bd("20"), 90, Frequency.WEEKLY, start)
        assertIs<SimulationResult.Success>(r)
        assertEquals(emptyList(), r.warnings)
        assertEquals(bd("20.00"), r.minimumDownPayment)
    }
}
