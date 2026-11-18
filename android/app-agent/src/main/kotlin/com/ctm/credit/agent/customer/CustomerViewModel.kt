package com.ctm.credit.agent.customer

import com.ctm.credit.agent.Json
import com.ctm.credit.agent.queuedItem
import com.ctm.credit.model.CongoPhone
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Formulaire de création d'un client.
 *
 * L'action "customer.create" est mise en file (SyncEngine) ; elle sera envoyée
 * au serveur lors du prochain appel à runOnce() (foreground ou worker).
 * Champs attendus par le serveur : id, phone_number, first_name, last_name,
 * national_id_number, address, city.
 * La photo KYC est gérée séparément via PhotoVault, après que customer.create est "applied".
 */
class CustomerViewModel(
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
) {
    data class Form(
        val firstName: String = "",
        val lastName: String = "",
        val phone: String = "",
        val nationalId: String = "",
        val address: String = "",
        /** Facultative : le serveur accepte une ville vide. */
        val city: String = "",
    )

    sealed class State {
        object Idle : State()
        object Saving : State()
        /** L'action est en file ; l'UI peut naviguer vers l'écran suivant. */
        data class Queued(val customerId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun submit(form: Form) {
        if (form.firstName.isBlank()) { _state.value = State.Error("Le prénom est obligatoire."); return }
        if (form.lastName.isBlank())  { _state.value = State.Error("Le nom est obligatoire."); return }
        if (!CongoPhone.isValid(form.phone)) {
            _state.value = State.Error("Numéro de téléphone invalide (format : +243XXXXXXXXX).")
            return
        }
        if (form.nationalId.isBlank()) { _state.value = State.Error("Le numéro de pièce d'identité est obligatoire."); return }
        if (form.address.isBlank())    { _state.value = State.Error("L'adresse est obligatoire."); return }

        _state.value = State.Saving
        scope.launch {
            val customerId = java.util.UUID.randomUUID().toString()
            val payload = Json.obj(
                "id"                 to customerId,
                "phone_number"       to form.phone.trim(),
                "first_name"         to form.firstName.trim(),
                "last_name"          to form.lastName.trim(),
                "national_id_number" to form.nationalId.trim(),
                "address"            to form.address.trim(),
                "city"               to form.city.trim().ifBlank { null },
            )
            syncEngine.enqueue(queuedItem(SyncAction.CUSTOMER_CREATE, payload, entityKey = customerId))
            _state.value = State.Queued(customerId)
        }
    }

    fun reset() { _state.value = State.Idle }
}
