package com.ctm.credit.model

import java.math.BigDecimal
import java.time.LocalDate

/** Inputs of the server function compute_offer(). All amounts are USD with at most 2 decimals. */
data class OfferInput(
    val retailPrice: BigDecimal,
    val downPayment: BigDecimal,
    val durationDays: Int,
    val frequency: Frequency,
    val startDate: LocalDate,
    val processingFeeRate: BigDecimal,
    val interestRatePer30Days: BigDecimal,
)

data class Installment(
    /** 1-based. The first installment is never due on the start date. */
    val number: Int,
    val dueDate: LocalDate,
    val amountDue: BigDecimal,
)

data class Offer(
    val processingFee: BigDecimal,
    val financedAmount: BigDecimal,
    val interestAmount: BigDecimal,
    val totalDue: BigDecimal,
    val installmentCount: Int,
    /** Amount of every installment except the last one. */
    val installmentAmount: BigDecimal,
    val lastInstallmentAmount: BigDecimal,
    val firstDueDate: LocalDate,
    val endDate: LocalDate,
    val schedule: List<Installment>,
)
