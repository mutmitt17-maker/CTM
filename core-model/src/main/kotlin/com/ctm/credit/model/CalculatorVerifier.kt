package com.ctm.credit.model

import java.math.BigDecimal
import java.time.LocalDate

/** Expected output of one server test vector (calculator_spec().test_vectors[].expected). */
data class ExpectedOffer(
    val processingFee: BigDecimal,
    val financedAmount: BigDecimal,
    val interestAmount: BigDecimal,
    val totalDue: BigDecimal,
    val installmentCount: Int,
    val installmentAmount: BigDecimal,
    val lastInstallmentAmount: BigDecimal,
    val firstDueDate: LocalDate,
    val endDate: LocalDate,
)

data class SpecVector(val input: OfferInput, val expected: ExpectedOffer)

/** Calculator spec as sent by offline_catalogue().calculator. */
data class CalculatorSpec(val version: String, val vectors: List<SpecVector>)

sealed interface VerificationResult {
    data object Ok : VerificationResult

    /** Offline simulation must be disabled ("Simulation en ligne uniquement") and the problem logged. */
    data class Disabled(val problems: List<String>) : VerificationResult
}

/**
 * Run after every catalogue download. If a single value differs, the offline simulator must be
 * switched off. An unknown version or an empty vector list also disables it (fail safe).
 */
object CalculatorVerifier {
    fun verify(spec: CalculatorSpec): VerificationResult {
        val problems = mutableListOf<String>()
        if (spec.version != LoanCalculator.VERSION) {
            problems += "unsupported calculator version ${spec.version} (local: ${LoanCalculator.VERSION})"
        }
        if (spec.vectors.isEmpty()) problems += "no test vectors received"

        spec.vectors.forEachIndexed { i, v ->
            val got = try {
                LoanCalculator.compute(v.input)
            } catch (e: IllegalArgumentException) {
                problems += "vector $i: ${e.message}"
                return@forEachIndexed
            }
            val e = v.expected
            fun diff(name: String, a: Any, b: Any, equal: Boolean) {
                if (!equal) problems += "vector $i: $name expected $b, got $a"
            }
            fun money(name: String, a: BigDecimal, b: BigDecimal) = diff(name, a, b, a.compareTo(b) == 0)
            money("processing_fee", got.processingFee, e.processingFee)
            money("financed_amount", got.financedAmount, e.financedAmount)
            money("interest_amount", got.interestAmount, e.interestAmount)
            money("total_due", got.totalDue, e.totalDue)
            diff("installment_count", got.installmentCount, e.installmentCount, got.installmentCount == e.installmentCount)
            money("installment_amount", got.installmentAmount, e.installmentAmount)
            money("last_installment_amount", got.lastInstallmentAmount, e.lastInstallmentAmount)
            diff("first_due_date", got.firstDueDate, e.firstDueDate, got.firstDueDate == e.firstDueDate)
            diff("end_date", got.endDate, e.endDate, got.endDate == e.endDate)
        }
        return if (problems.isEmpty()) VerificationResult.Ok else VerificationResult.Disabled(problems)
    }
}
