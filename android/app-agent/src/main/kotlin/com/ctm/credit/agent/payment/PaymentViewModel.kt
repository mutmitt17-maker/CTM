package com.ctm.credit.agent.payment

import com.ctm.credit.network.IdempotencyKeys
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.network.SyncItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ViewModel de l'écran d'encaissement.
 *
 * L'agent enregistre un paiement en espèces pour un contrat (action "payment.cash").
 * L'action est mise en file et envoyée lors du prochain cycle de synchronisation.
 */
class PaymentViewModel(
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
) {
    data class Form(
        val contractNumber: String = "",
        val amount: String = "",
        val note: String = "",
    )

    sealed class State {
        object Idle : State()
        object Saving : State()
        data class Queued(val contractNumber: String, val amount: Double) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun submit(form: Form) {
        if (form.contractNumber.isBlank()) { _state.value = State.Error("Le numéro de contrat est obligatoire."); return }
        val amount = form.amount.trim().replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) { _state.value = State.Error("Le montant doit être un nombre positif."); return }

        _state.value = State.Saving
        scope.launch {
            val noteJson = if (form.note.isBlank()) "null" else "\"${form.note.replace("\"", "\\\"")}\""
            val payload = """{
                "contract_number":"${form.contractNumber.trim()}",
                "amount":$amount,
                "note":$noteJson,
                "collected_at":"${Instant.now()}"
            }""".trimIndent()

            syncEngine.enqueue(
                SyncItem(
                    localId         = System.currentTimeMillis(),
                    idempotencyKey  = IdempotencyKeys.new(),
                    action          = SyncAction.PAYMENT_CASH,
                    payloadJson     = payload,
                    entityKey       = form.contractNumber.trim(),
                    clientCreatedAt = Instant.now(),
                    clientUpdatedAt = Instant.now(),
                )
            )
            _state.value = State.Queued(form.contractNumber.trim(), amount)
        }
    }

    fun reset() { _state.value = State.Idle }
}
