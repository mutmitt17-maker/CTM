package com.ctm.credit.agent.delivery

import com.ctm.credit.model.Validators
import com.ctm.credit.network.IdempotencyKeys
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.network.SyncItem
import com.ctm.credit.security.PhotoVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * ViewModel du flux de livraison.
 *
 * Étapes dans l'ordre :
 *   1. [startDelivery]  : prépare la livraison (action "start_delivery").
 *   2. [verifyImei]     : scanne et vérifie l'IMEI (action "verify_delivery_imei").
 *   3. [recordProof]    : enregistre la photo de preuve + signature (action "record_handover_proof").
 *   4. [completeDelivery]: clôture la livraison (action "complete_delivery").
 *
 * Les photos passent par PhotoVault ; elles sont supprimées dès l'envoi confirmé par le serveur.
 */
class DeliveryViewModel(
    private val syncEngine: SyncEngine,
    private val photoVault: PhotoVault,
    private val scope: CoroutineScope,
) {
    sealed class State {
        object Idle : State()
        data class Started(val deliveryId: String) : State()
        data class ImeiVerified(val deliveryId: String, val imei: String) : State()
        data class ProofRecorded(val deliveryId: String) : State()
        data class Completed(val deliveryId: String) : State()
        data class Error(val message: String, val previousState: State) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun startDelivery(applicationId: String) {
        if (applicationId.isBlank()) { _state.value = State.Error("ID de dossier manquant.", State.Idle); return }
        scope.launch {
            val deliveryId = java.util.UUID.randomUUID().toString()
            enqueue(
                action    = SyncAction.START_DELIVERY,
                payload   = """{"delivery_id":"$deliveryId","application_id":"$applicationId"}""",
                entityKey = applicationId,
            )
            _state.value = State.Started(deliveryId)
        }
    }

    fun verifyImei(deliveryId: String, imei: String) {
        if (!Validators.isValidImei(imei)) {
            _state.value = State.Error("IMEI invalide (15 chiffres, vérification de Luhn échouée).", State.Started(deliveryId))
            return
        }
        scope.launch {
            enqueue(
                action    = SyncAction.VERIFY_DELIVERY_IMEI,
                payload   = """{"delivery_id":"$deliveryId","imei":"$imei"}""",
                entityKey = deliveryId,
            )
            _state.value = State.ImeiVerified(deliveryId, imei)
        }
    }

    /**
     * [photoPath] : chemin dans le bucket Storage (ex. "<delivery_id>/proof.jpg").
     * [signaturePath] : idem pour la signature.
     * Les fichiers doivent exister dans PhotoVault avant l'appel.
     */
    fun recordProof(deliveryId: String, photoPath: String, signaturePath: String) {
        if (photoPath.isBlank() || signaturePath.isBlank()) {
            _state.value = State.Error("Photo et signature obligatoires.", State.ImeiVerified(deliveryId, ""))
            return
        }
        scope.launch {
            enqueue(
                action    = SyncAction.RECORD_HANDOVER_PROOF,
                payload   = """{"delivery_id":"$deliveryId","photo_path":"$photoPath","signature_path":"$signaturePath"}""",
                entityKey = deliveryId,
            )
            _state.value = State.ProofRecorded(deliveryId)
        }
    }

    fun completeDelivery(deliveryId: String) {
        scope.launch {
            enqueue(
                action    = SyncAction.COMPLETE_DELIVERY,
                payload   = """{"delivery_id":"$deliveryId"}""",
                entityKey = deliveryId,
            )
            _state.value = State.Completed(deliveryId)
        }
    }

    private fun enqueue(action: SyncAction, payload: String, entityKey: String) {
        syncEngine.enqueue(
            SyncItem(
                localId         = System.currentTimeMillis(),
                idempotencyKey  = IdempotencyKeys.new(),
                action          = action,
                payloadJson     = payload,
                entityKey       = entityKey,
                clientCreatedAt = Instant.now(),
                clientUpdatedAt = Instant.now(),
            )
        )
    }
}
