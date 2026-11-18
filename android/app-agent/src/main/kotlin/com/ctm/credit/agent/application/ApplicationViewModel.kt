package com.ctm.credit.agent.application

import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.agent.Json
import com.ctm.credit.agent.parseMoney
import com.ctm.credit.agent.queuedItem
import com.ctm.credit.model.*
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
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
        /** Dossier en file ; l'identifiant du dossier est attribué par le serveur (dans le résultat de la synchronisation). */
        data class Queued(val customerId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** Recalcule l'échéancier localement sans appel réseau. */
    fun simulate(form: Form) {
        val catalogItem = form.item ?: run { _state.value = State.Error("Choisissez un téléphone."); return }
        val down = parseMoney(form.downPayment)
        if (down == null) { _state.value = State.Error("Acompte invalide (nombre positif, 2 décimales au plus)."); return }

        val catalogueItem = CatalogueItem(
            itemId       = catalogItem.id,
            brand        = catalogItem.brand,
            model        = catalogItem.model,
            color        = null,
            retailPrice  = BigDecimal.valueOf(catalogItem.retailPrice).setScale(2, RoundingMode.HALF_UP),
            minDownPaymentPct = null,
            imeiSuffix   = null,
        )
        val terms = CreditTerms(
            downPaymentMinPct      = BigDecimal.valueOf(policy.downPaymentMinPct).setScale(4, RoundingMode.HALF_UP),
            downPaymentMaxPct      = BigDecimal.valueOf(policy.downPaymentMaxPct).setScale(4, RoundingMode.HALF_UP),
            allowedDurations       = policy.allowedDurations,
            processingFeeRate      = BigDecimal.valueOf(policy.processingFeeRate).setScale(4, RoundingMode.HALF_UP),
            interestRatePer30Days  = BigDecimal.valueOf(policy.interestRatePer30Days).setScale(4, RoundingMode.HALF_UP),
            penaltyRatePerDay      = BigDecimal.valueOf(policy.penaltyRatePerDay).setScale(4, RoundingMode.HALF_UP),
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

    /**
     * Met le dossier en file après que l'agent a confirmé la simulation.
     * Le serveur recalcule l'offre ; `expected_total_due` lui permet de refuser (offer_changed)
     * si le prix ou la politique ont changé depuis la simulation.
     */
    fun submit(form: Form, offer: Offer) {
        val catalogItem = form.item ?: run { _state.value = State.Error("Choisissez un téléphone."); return }
        val down = parseMoney(form.downPayment) ?: run {
            _state.value = State.Error("Acompte invalide."); return
        }
        if (form.customerId.isBlank()) { _state.value = State.Error("Client non sélectionné."); return }

        _state.value = State.Submitting
        scope.launch {
            val payload = Json.obj(
                "customer_id"        to form.customerId,
                "item_id"            to catalogItem.id,
                "down_payment"       to down,
                "duration_days"      to form.durationDays,
                "frequency"          to form.frequency.wire,
                "expected_total_due" to offer.totalDue,
            )
            syncEngine.enqueue(queuedItem(SyncAction.APPLICATION_SUBMIT, payload, entityKey = form.customerId))
            _state.value = State.Queued(form.customerId)
        }
    }

    fun reset() { _state.value = State.Idle }
}
