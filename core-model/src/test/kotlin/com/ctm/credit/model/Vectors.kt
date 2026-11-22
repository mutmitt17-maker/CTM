package com.ctm.credit.model

import java.math.BigDecimal
import java.time.LocalDate

private fun bd(s: String) = BigDecimal(s)

/**
 * The 5 test vectors of the server (calculator_spec().test_vectors).
 * The expected values were produced by an independent implementation of the written rules and
 * checked by hand. When the real server output is available, replace them with
 * `select public.calculator_spec();` and the test below must still pass unchanged.
 */
val serverVectors: List<SpecVector> = listOf(
    SpecVector(
        input = OfferInput(bd("100"), bd("30"), 90, Frequency.WEEKLY, LocalDate.of(2026, 1, 1), bd("0.03"), bd("0.06")),
        expected = ExpectedOffer(
            processingFee = bd("3.00"), financedAmount = bd("73.00"), interestAmount = bd("13.14"),
            totalDue = bd("86.14"), installmentCount = 13, installmentAmount = bd("6.63"),
            lastInstallmentAmount = bd("6.58"),
            firstDueDate = LocalDate.of(2026, 1, 8),
            endDate = LocalDate.of(2026, 4, 2),
        ),
    ),
    SpecVector(
        input = OfferInput(bd("149.99"), bd("45"), 60, Frequency.DAILY, LocalDate.of(2026, 1, 31), bd("0.03"), bd("0.06")),
        expected = ExpectedOffer(
            processingFee = bd("4.50"), financedAmount = bd("109.49"), interestAmount = bd("13.14"),
            totalDue = bd("122.63"), installmentCount = 60, installmentAmount = bd("2.04"),
            lastInstallmentAmount = bd("2.27"),
            firstDueDate = LocalDate.of(2026, 2, 1),
            endDate = LocalDate.of(2026, 4, 1),
        ),
    ),
    SpecVector(
        input = OfferInput(bd("250"), bd("75"), 120, Frequency.MONTHLY, LocalDate.of(2026, 1, 31), bd("0.03"), bd("0.06")),
        expected = ExpectedOffer(
            processingFee = bd("7.50"), financedAmount = bd("182.50"), interestAmount = bd("43.80"),
            totalDue = bd("226.30"), installmentCount = 4, installmentAmount = bd("56.58"),
            lastInstallmentAmount = bd("56.56"),
            firstDueDate = LocalDate.of(2026, 2, 28),
            endDate = LocalDate.of(2026, 5, 31),
        ),
    ),
    SpecVector(
        input = OfferInput(bd("80.50"), bd("24.15"), 30, Frequency.WEEKLY, LocalDate.of(2026, 2, 15), bd("0"), bd("0")),
        expected = ExpectedOffer(
            processingFee = bd("0.00"), financedAmount = bd("56.35"), interestAmount = bd("0.00"),
            totalDue = bd("56.35"), installmentCount = 5, installmentAmount = bd("11.27"),
            lastInstallmentAmount = bd("11.27"),
            firstDueDate = LocalDate.of(2026, 2, 22),
            endDate = LocalDate.of(2026, 3, 22),
        ),
    ),
    SpecVector(
        input = OfferInput(bd("199.99"), bd("60"), 180, Frequency.MONTHLY, LocalDate.of(2026, 3, 1), bd("0.05"), bd("0.075")),
        expected = ExpectedOffer(
            processingFee = bd("10.00"), financedAmount = bd("149.99"), interestAmount = bd("67.50"),
            totalDue = bd("217.49"), installmentCount = 6, installmentAmount = bd("36.25"),
            lastInstallmentAmount = bd("36.24"),
            firstDueDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 9, 1),
        ),
    ),)
