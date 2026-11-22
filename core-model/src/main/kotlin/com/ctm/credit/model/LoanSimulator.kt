package com.ctm.credit.model

import java.math.BigDecimal
import java.time.LocalDate

/** Credit terms as sent by offline_catalogue().terms. Percentages are plain numbers (20 means 20 %). */
data class CreditTerms(
    val downPaymentMinPct: BigDecimal,
    val downPaymentMaxPct: BigDecimal,
    val allowedDurations: List<Int>,
    val processingFeeRate: BigDecimal,
    val interestRatePer30Days: BigDecimal,
    val penaltyRatePerDay: BigDecimal,
    val graceDays: Int,
    val lockAfterDaysOverdue: Int,
)

/** One physical phone in stock, from offline_catalogue().items. */
data class CatalogueItem(
    val itemId: String,
    val brand: String,
    val model: String,
    val color: String?,
    val retailPrice: BigDecimal,
    val minDownPaymentPct: BigDecimal?,
    val imeiSuffix: String?,
)

enum class SimulationWarning { DOWN_PAYMENT_ABOVE_USUAL_RANGE }

enum class SimulationRefusal { DURATION_NOT_OFFERED, DOWN_PAYMENT_BELOW_MINIMUM, DOWN_PAYMENT_ABOVE_PRICE }

sealed interface SimulationResult {
    data class Success(
        val offer: Offer,
        val minimumDownPayment: BigDecimal,
        val warnings: List<SimulationWarning>,
    ) : SimulationResult

    data class Refused(
        val reason: SimulationRefusal,
        /** Minimum down payment in USD, always filled so the screen can show it. */
        val minimumDownPayment: BigDecimal,
    ) : SimulationResult
}

/** Mirrors the checks of the server function simulate_loan(). */
object LoanSimulator {
    fun simulate(
        item: CatalogueItem,
        terms: CreditTerms,
        downPayment: BigDecimal,
        durationDays: Int,
        frequency: Frequency,
        startDate: LocalDate,
    ): SimulationResult {
        val minPct = item.minDownPaymentPct ?: terms.downPaymentMinPct
        val minDown = LoanCalculator.percentOf(item.retailPrice, minPct)

        if (durationDays !in terms.allowedDurations) {
            return SimulationResult.Refused(SimulationRefusal.DURATION_NOT_OFFERED, minDown)
        }
        if (downPayment < minDown) {
            return SimulationResult.Refused(SimulationRefusal.DOWN_PAYMENT_BELOW_MINIMUM, minDown)
        }
        if (downPayment > item.retailPrice) {
            return SimulationResult.Refused(SimulationRefusal.DOWN_PAYMENT_ABOVE_PRICE, minDown)
        }

        val warnings = buildList {
            if (downPayment > LoanCalculator.percentOf(item.retailPrice, terms.downPaymentMaxPct)) {
                add(SimulationWarning.DOWN_PAYMENT_ABOVE_USUAL_RANGE)
            }
        }

        val offer = LoanCalculator.compute(
            OfferInput(
                retailPrice = item.retailPrice,
                downPayment = downPayment,
                durationDays = durationDays,
                frequency = frequency,
                startDate = startDate,
                processingFeeRate = terms.processingFeeRate,
                interestRatePer30Days = terms.interestRatePer30Days,
            ),
        )
        return SimulationResult.Success(offer, minDown, warnings)
    }
}
