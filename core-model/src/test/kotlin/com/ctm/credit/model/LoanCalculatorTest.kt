package com.ctm.credit.model

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoanCalculatorTest {
    private fun bd(s: String) = BigDecimal(s)

    @Test
    fun `the 5 server vectors are reproduced exactly`() {
        assertEquals(VerificationResult.Ok, allVectorsMatch())
    }

    @Test
    fun `hand checked weekly offer`() {
        val o = LoanCalculator.compute(
            OfferInput(bd("100"), bd("30"), 90, Frequency.WEEKLY, LocalDate.of(2026, 1, 1), bd("0.03"), bd("0.06")),
        )
        assertEquals(bd("3.00"), o.processingFee)
        assertEquals(bd("73.00"), o.financedAmount)
        assertEquals(bd("13.14"), o.interestAmount)
        assertEquals(bd("86.14"), o.totalDue)
        assertEquals(13, o.installmentCount)
        assertEquals(bd("6.63"), o.installmentAmount)
        assertEquals(bd("6.58"), o.lastInstallmentAmount)
        assertEquals(LocalDate.of(2026, 1, 8), o.firstDueDate)
        assertEquals(LocalDate.of(2026, 4, 2), o.endDate)
    }

    @Test
    fun `fee rounds half away from zero`() {
        // 199.99 * 0.05 = 9.9995 exactly: a tie that must round up to 10.00
        val o = LoanCalculator.compute(
            OfferInput(bd("199.99"), bd("60"), 180, Frequency.MONTHLY, LocalDate.of(2026, 3, 1), bd("0.05"), bd("0.075")),
        )
        assertEquals(bd("10.00"), o.processingFee)
    }

    @Test
    fun `schedule always adds up to the total and the last installment absorbs the gap`() {
        for (v in serverVectors) {
            val o = LoanCalculator.compute(v.input)
            assertEquals(o.installmentCount, o.schedule.size)
            val sum = o.schedule.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amountDue) }
            assertEquals(0, sum.compareTo(o.totalDue))
            assertEquals(0, o.schedule.last().amountDue.compareTo(o.lastInstallmentAmount))
        }
    }

    @Test
    fun `monthly dates are counted from the start date, not month after month`() {
        val o = LoanCalculator.compute(
            OfferInput(bd("250"), bd("75"), 120, Frequency.MONTHLY, LocalDate.of(2026, 1, 31), bd("0.03"), bd("0.06")),
        )
        assertEquals(
            listOf(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 31)),
            o.schedule.map { it.dueDate },
        )
    }

    @Test
    fun `first installment is never due on the start date`() {
        for (f in Frequency.entries) {
            val o = LoanCalculator.compute(
                OfferInput(bd("100"), bd("20"), 60, f, LocalDate.of(2026, 5, 10), bd("0"), bd("0")),
            )
            assertTrue(o.firstDueDate.isAfter(LocalDate.of(2026, 5, 10)))
        }
    }

    @Test
    fun `installment count is the ceiling of duration over period`() {
        assertEquals(2, LoanCalculator.installmentCount(60, Frequency.MONTHLY))
        assertEquals(13, LoanCalculator.installmentCount(90, Frequency.WEEKLY))
        assertEquals(5, LoanCalculator.installmentCount(30, Frequency.WEEKLY))
        assertEquals(60, LoanCalculator.installmentCount(60, Frequency.DAILY))
    }

    @Test
    fun `a wrong local result disables the offline simulator`() {
        val broken = serverVectors.first().let {
            it.copy(expected = it.expected.copy(totalDue = bd("999.99")))
        }
        val r = CalculatorVerifier.verify(CalculatorSpec("v1", listOf(broken)))
        assertIs<VerificationResult.Disabled>(r)
    }

    @Test
    fun `unknown version or empty vectors disable the offline simulator`() {
        assertIs<VerificationResult.Disabled>(CalculatorVerifier.verify(CalculatorSpec("v2", serverVectors)))
        assertIs<VerificationResult.Disabled>(CalculatorVerifier.verify(CalculatorSpec("v1", emptyList())))
    }
}
