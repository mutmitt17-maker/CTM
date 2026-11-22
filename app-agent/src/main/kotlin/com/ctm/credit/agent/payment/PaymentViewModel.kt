package com.ctm.credit.agent.payment

import com.ctm.credit.agent.Json
import com.ctm.credit.agent.parseMoney
import com.ctm.credit.agent.queuedItem
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant

/**
 * ViewModel de l'écran d'encaissement.
 *
 * L'agent saisit le numéro du contrat (ex. CT-2026-000123) et le montant reçu en espèces.
 * L'action "payment.cash" est mise en file et envoyée au prochain cycle de synchronisation ;
 * le serveur retrouve le contrat à partir de `contract_number` (numéro unique).
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
        data class Queued(val contractNumber: String, val amount: BigDecimal) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun submit(form: Form) {
        val number = form.contractNumber.trim().uppercase()
        if (number.isEmpty()) { _state.value = State.Error("Le numéro de contrat est obligatoire."); return }
        if (!CONTRACT_NUMBER.matches(number)) {
            _state.value = State.Error("Numéro de contrat invalide (format : CT-2026-000123).")
            return
        }
        val amount = parseMoney(form.amount)
        if (amount == null || amount.signum() <= 0) {
            _state.value = State.Error("Le montant doit être un nombre positif, avec 2 décimales au plus.")
            return
        }

        _state.value = State.Saving
        scope.launch {
            val payload = Json.obj(
                "contract_number" to number,
                "amount"          to amount,
                "note"            to form.note.trim().ifBlank { null },
                "collected_at"    to Instant.now().toString(),
            )
            syncEngine.enqueue(queuedItem(SyncAction.PAYMENT_CASH, payload, entityKey = number))
            _state.value = State.Queued(number, amount)
        }
    }

    fun reset() { _state.value = State.Idle }

    private companion object {
        val CONTRACT_NUMBER = Regex("""CT-\d{4}-\d{6}""")
    }
}
