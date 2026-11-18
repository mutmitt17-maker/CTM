package com.ctm.credit.agent.application

import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.model.*
import com.ctm.credit.network.IdempotencyKeys
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.network.SyncItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * ViewModel de l'écran de création de dossier de crédit.
 *
 * L'agent choisit un téléphone, un acompte, une durée et une fréquence.
 * [simulate] recalcule l'échéancier hors ligne (LoanSimulator).
 * [submit] met l'action "application.submit" en file (SyncEngine).
 */
class ApplicationViewModel(
    private val syncEngine: SyncEngine,
    private val policy: CreditPolicy,
    private val scope: CoroutineScope,
) {
    data class Form(
        val customerId: String = "",
        val item: CatalogItem? = null,
        val downPayment: String = "",
        val durationDays: Int = 30,
        val frequency: Frequency = Frequency.MONTHLY,
    )

    sealed class State {
        object Idle : State()
        /** Simulation hors ligne réussie ; l'UI affiche le résumé. */
        data class SimulationReady(
            val offer: Offer,
            val warnings: List<SimulationWarning>,
        ) : State()
        object Submitting : State()
        data class Queued(val applicationId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Recalcule l'échéancier localement sans appel réseau. */
    fun simulate(form: Form) {
        val catalogItem = form.item ?: run { _state.value = State.Error("Choisissez un téléphone."); return }
        val down = form.downPayment.trim().toBigDecimalOrNull()
        if (down == null || down < BigDecimal.ZERO) { _state.value = State.Error("Acompte invalide."); return }

        val catalogueItem = CatalogueItem(
            itemId       = catalogItem.id,
            brand        = catalogItem.brand,
            model        = catalogItem.model,
            color        = null,
            retailPrice  = BigDecimal(catalogItem.retailPrice).setScale(2),
            minDownPaymentPct = null,
            imeiSuffix   = null,
        )
        val terms = CreditTerms(
            downPaymentMinPct      = BigDecimal(policy.downPaymentMinPct).setScale(4),
            downPaymentMaxPct      = BigDecimal(policy.downPaymentMaxPct).setScale(4),
            allowedDurations       = policy.allowedDurations,
            processingFeeRate      = BigDecimal(policy.processingFeeRate).setScale(4),
            interestRatePer30Days  = BigDecimal(policy.interestRatePer30Days).setScale(4),
            penaltyRatePerDay      = BigDecimal(policy.penaltyRatePerDay).setScale(4),
            graceDays              = policy.graceDays,
            lockAfterDaysOverdue   = policy.lockAfterDaysOverdue,
        )

        when (val result = LoanSimulator.simulate(
            item        = catalogueItem,
            terms       = terms,
            downPayment = down,
            durationDays = form.durationDays,
            frequency   = form.frequency,
            startDate   = LocalDate.now(),
        )) {
            is SimulationResult.Success -> _state.value = State.SimulationReady(result.offer, result.warnings)
            is SimulationResult.Refused -> _state.value = State.Error(
                when (result.reason) {
                    SimulationRefusal.DURATION_NOT_OFFERED     -> "Durée non autorisée par la politique de crédit."
                    SimulationRefusal.DOWN_PAYMENT_BELOW_MINIMUM -> "Acompte minimum : %.2f USD.".format(result.minimumDownPayment)
                    SimulationRefusal.DOWN_PAYMENT_ABOVE_PRICE  -> "L'acompte ne peut pas dépasser le prix du téléphone."
                }
            )
        }
    }

    /** Met le dossier en file après que l'agent a confirmé la simulation. */
    fun submit(form: Form, offer: Offer) {
        val catalogItem = form.item ?: run { _state.value = State.Error("Choisissez un téléphone."); return }
        val down = form.downPayment.trim().toBigDecimalOrNull() ?: run {
            _state.value = State.Error("Acompte invalide."); return
        }
        if (form.customerId.isBlank()) { _state.value = State.Error("Client non sélectionné."); return }

        _state.value = State.Submitting
        scope.launch {
            val appId = java.util.UUID.randomUUID().toString()
            // Sérialisation minimale de l'échéancier (remplacée par Gson dans :app-agent Android)
            val scheduleJson = offer.schedule.joinToString(",", "[", "]") { inst ->
                """{"number":${inst.number},"due_date":"${inst.dueDate}","amount_due":${inst.amountDue}}"""
            }
            val payload = """{
                "id":"$appId",
                "customer_id":"${form.customerId}",
                "item_id":"${catalogItem.id}",
                "down_payment":${down},
                "duration_days":${form.durationDays},
                "frequency":"${form.frequency.name.lowercase()}",
                "processing_fee":${offer.processingFee},
                "financed_amount":${offer.financedAmount},
                "interest_amount":${offer.interestAmount},
                "total_due":${offer.totalDue},
                "installment_plan":$scheduleJson
            }""".trimIndent()

            val syncItem = SyncItem(
                localId         = System.currentTimeMillis(),
                idempotencyKey  = IdempotencyKeys.new(),
                action          = SyncAction.APPLICATION_SUBMIT,
                payloadJson     = payload,
                entityKey       = form.customerId,
                clientCreatedAt = Instant.now(),
                clientUpdatedAt = Instant.now(),
            )
            syncEngine.enqueue(syncItem)
            _state.value = State.Queued(appId)
        }
    }

    fun reset() { _state.value = State.Idle }
}
