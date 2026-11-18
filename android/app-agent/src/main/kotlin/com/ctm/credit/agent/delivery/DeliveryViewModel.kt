package com.ctm.credit.agent.delivery

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.model.Imei
import com.ctm.credit.security.DeviceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel du flux de livraison.
 *
 * La livraison exige le serveur : ce sont des appels directs en ligne (pas des actions de
 * `sync_batch`, que le serveur refuserait avec `unknown_action`). Étapes dans l'ordre :
 *   1. [startDelivery]   : le serveur crée la livraison et renvoie son identifiant.
 *   2. [verifyImei]      : scan de l'IMEI, comparé à celui du téléphone réservé.
 *   3. [recordProof]     : photo de remise (chemin dans le bucket `handover`) + empreinte de la signature.
 *   4. [completeDelivery]: clôture.
 *
 * Les photos sont gardées dans PhotoVault et envoyées par le worker d'envoi ; ce ViewModel ne manipule que leurs chemins.
 */
class DeliveryViewModel(
    private val api: AgentApi,
    private val deviceFingerprint: DeviceFingerprint,
    private val scope: CoroutineScope,
) {
    sealed class State {
        object Idle : State()
        object Working : State()
        data class Started(val deliveryId: String) : State()
        data class ImeiVerified(val deliveryId: String, val imei: String) : State()
        data class ProofRecorded(val deliveryId: String) : State()
        data class Completed(val deliveryId: String) : State()
        /** [previousState] : l'étape à laquelle l'agent peut revenir pour réessayer. */
        data class Error(val message: String, val previousState: State) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun startDelivery(applicationId: String) {
        if (applicationId.isBlank()) { fail("ID de dossier manquant.", State.Idle); return }
        run(State.Idle) {
            api.startDelivery(applicationId).fold(State.Idle) { deliveryId ->
                _state.value = State.Started(deliveryId)
            }
        }
    }

    fun verifyImei(deliveryId: String, imei: String) {
        val previous = State.Started(deliveryId)
        if (!Imei.isValid(imei)) {
            fail("IMEI invalide (15 chiffres, vérification de Luhn échouée).", previous)
            return
        }
        run(previous) {
            api.verifyDeliveryImei(deliveryId, imei).fold(previous) { check ->
                if (!check.matched) {
                    fail("Cet IMEI ne correspond pas au téléphone réservé.", previous)
                    return@fold
                }
                _state.value = State.ImeiVerified(deliveryId, imei)
            }
        }
    }

    /**
     * [photoPath] : chemin dans le bucket `handover` (ex. "<delivery_id>/proof.jpg").
     * [signatureHash] : SHA-256 (hexadécimal) de l'image de la signature.
     */
    fun recordProof(deliveryId: String, photoPath: String, signatureHash: String) {
        val previous = State.ImeiVerified(deliveryId, "")
        if (photoPath.isBlank() || signatureHash.isBlank()) {
            fail("Photo et signature obligatoires.", previous)
            return
        }
        run(previous) {
            api.recordHandoverProof(deliveryId, photoPath, signatureHash, deviceFingerprint.get())
                .fold(previous) { _state.value = State.ProofRecorded(deliveryId) }
        }
    }

    fun completeDelivery(deliveryId: String) {
        val previous = State.ProofRecorded(deliveryId)
        run(previous) {
            api.completeDelivery(deliveryId).fold(previous) { _state.value = State.Completed(deliveryId) }
        }
    }

    fun reset() { _state.value = State.Idle }

    private fun fail(message: String, previous: State) {
        _state.value = State.Error(message, previous)
    }

    /** Succès : exécute [onOk] ; échec : passe en erreur et garde [previous] comme étape de reprise. */
    private fun <T> ApiResult<T>.fold(previous: State, onOk: (T) -> Unit) {
        when (this) {
            is ApiResult.Ok -> onOk(value)
            is ApiResult.Failure -> fail(message, previous)
        }
    }

    /** Lance un appel réseau ; toute exception (hors annulation) devient un message d'erreur. */
    private fun run(previous: State, block: suspend () -> Unit) {
        _state.value = State.Working
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Erreur réseau. Réessayez quand la connexion revient.", previous)
            }
        }
    }
}
