package com.ctm.credit.agent.customer

import com.ctm.credit.model.Validators
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
 * Formulaire de création d'un client.
 *
 * L'action "customer.create" est mise en file (SyncEngine) ; elle sera envoyée
 * au serveur lors du prochain appel à runOnce() (foreground ou worker).
 * La photo KYC est gérée séparément via PhotoVault + StorageUploadWorker.
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
        val monthlyIncome: String = "",
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
        // Validations locales
        if (form.firstName.isBlank()) { _state.value = State.Error("Le prénom est obligatoire."); return }
        if (form.lastName.isBlank())  { _state.value = State.Error("Le nom est obligatoire."); return }
        if (!Validators.isValidCongoPhone(form.phone)) {
            _state.value = State.Error("Numéro de téléphone invalide (format : +243XXXXXXXXX).")
            return
        }
        if (form.nationalId.isBlank()) { _state.value = State.Error("Le numéro de pièce d'identité est obligatoire."); return }
        if (form.address.isBlank())    { _state.value = State.Error("L'adresse est obligatoire."); return }
        val income = form.monthlyIncome.trim().toDoubleOrNull()
        if (income == null || income < 0) { _state.value = State.Error("Le revenu mensuel est invalide."); return }

        _state.value = State.Saving
        scope.launch {
            val customerId = java.util.UUID.randomUUID().toString()
            val payload = buildJsonObject(
                "id"             to customerId,
                "first_name"     to form.firstName.trim(),
                "last_name"      to form.lastName.trim(),
                "phone"          to form.phone.trim(),
                "national_id"    to form.nationalId.trim(),
                "address"        to form.address.trim(),
                "monthly_income" to income,
            )
            val item = SyncItem(
                localId          = System.currentTimeMillis(),
                idempotencyKey   = IdempotencyKeys.new(),
                action           = SyncAction.CUSTOMER_CREATE,
                payloadJson      = payload,
                entityKey        = customerId,
                clientCreatedAt  = Instant.now(),
                clientUpdatedAt  = Instant.now(),
            )
            syncEngine.enqueue(item)
            _state.value = State.Queued(customerId)
        }
    }

    fun reset() { _state.value = State.Idle }

    // Minimal JSON builder (remplacé par Gson/Moshi dans l'implémentation Android)
    private fun buildJsonObject(vararg pairs: Pair<String, Any?>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            "\"$k\":${if (v is String) "\"${v.replace("\"", "\\\"")}\"" else v}"
        }
}
