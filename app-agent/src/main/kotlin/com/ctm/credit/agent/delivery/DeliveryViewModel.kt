package com.ctm.credit.agent.delivery

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.MdmStatus
import com.ctm.credit.model.Imei
import com.ctm.credit.security.DeviceFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel du flux de livraison.
 *
 * La livraison exige le serveur : ce sont des appels directs en ligne (pas des actions de
 * `sync_batch`, que le serveur refuserait avec `unknown_action`). Étapes dans l'ordre :
 *   1. [startDelivery]        : le serveur crée la livraison et renvoie son identifiant.
 *   2. [verifyImei]           : scan ou saisie de l'IMEI, comparé à celui du téléphone réservé.
 *   3. [recordProofWithUpload]: upload photo JPEG + signature PNG vers bucket `handover`, puis `record_handover_proof`.
 *   4. [requestEnrollment]    : demande d'enrôlement MDM (verrouillage à distance du téléphone).
 *   5. [startEnrollmentPolling] / [checkEnrollment] : interroge `delivery.mdm_status` jusqu'à `enrolled`.
 *   6. [completeDelivery]     : clôture ; le serveur la refuse tant que 2, 3 et 5 ne sont pas confirmés.
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
        /** Enrôlement demandé : en attente de la confirmation du serveur (voir [checkEnrollment]). */
        data class EnrollmentRequested(val deliveryId: String) : State()
        /** Le serveur a confirmé l'enrôlement MDM : la clôture est possible. */
        data class Enrolled(val deliveryId: String) : State()
        data class Completed(val deliveryId: String) : State()
        /** [previousState] : l'étape à laquelle l'agent peut revenir pour réessayer. */
        data class Error(val message: String, val previousState: State) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _mdmPolling = MutableStateFlow(false)
    val mdmPolling: StateFlow<Boolean> = _mdmPolling

    private val _mdmPollTimedOut = MutableStateFlow(false)
    val mdmPollTimedOut: StateFlow<Boolean> = _mdmPollTimedOut

    private var pollJob: Job? = null

    fun restore(savedState: State) {
        if (_state.value == State.Idle && savedState !is State.Idle && savedState !is State.Working && savedState !is State.Completed && savedState !is State.Error) {
            _state.value = savedState
        }
    }

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

    /**
     * Envoie d'abord la photo de remise (JPEG) et la signature (PNG) dans le bucket privé `handover`,
     * puis enregistre la preuve via `record_handover_proof`.
     */
    fun recordProofWithUpload(
        deliveryId: String,
        photoObjectPath: String,
        photoBytes: ByteArray,
        signatureObjectPath: String,
        signaturePngBytes: ByteArray,
        signatureHash: String,
    ) {
        val previous = State.ImeiVerified(deliveryId, "")
        if (photoObjectPath.isBlank() || photoBytes.isEmpty() || signaturePngBytes.isEmpty() || signatureHash.isBlank()) {
            fail("Photo et signature obligatoires.", previous)
            return
        }
        run(previous) {
            when (val photoRes = api.uploadHandoverObject(photoObjectPath, photoBytes, "image/jpeg")) {
                is ApiResult.Failure -> {
                    fail(photoRes.message, previous)
                    return@run
                }
                is ApiResult.Ok -> Unit
            }
            when (val sigRes = api.uploadHandoverObject(signatureObjectPath, signaturePngBytes, "image/png")) {
                is ApiResult.Failure -> {
                    fail(sigRes.message, previous)
                    return@run
                }
                is ApiResult.Ok -> Unit
            }
            api.recordHandoverProof(deliveryId, photoObjectPath, signatureHash, deviceFingerprint.get())
                .fold(previous) { _state.value = State.ProofRecorded(deliveryId) }
        }
    }

    /** Demande l'enrôlement MDM du téléphone remis (après la preuve de remise). */
    fun requestEnrollment(deliveryId: String) {
        stopPolling()
        val previous = State.ProofRecorded(deliveryId)
        run(previous) {
            api.requestMdmEnrollment(deliveryId).fold(previous) { _state.value = State.EnrollmentRequested(deliveryId) }
        }
    }

    /**
     * Interroge le serveur une fois sur l'enrôlement.
     */
    fun checkEnrollment(deliveryId: String) {
        val waiting = State.EnrollmentRequested(deliveryId)
        run(waiting, showWorking = false) {
            api.mdmStatus(deliveryId).fold(waiting) { onMdmStatus(deliveryId, it) }
        }
    }

    /**
     * Démarre un polling automatique toutes les [intervalMs] ms jusqu'à confirmation `enrolled`,
     * échec MDM (`failed` / `not_started`), ou dépassement de [maxAttempts].
     */
    fun startEnrollmentPolling(
        deliveryId: String,
        intervalMs: Long = 4_000L,
        maxAttempts: Int = 22,
    ) {
        if (pollJob?.isActive == true) return
        _mdmPollTimedOut.value = false
        _mdmPolling.value = true
        pollJob = scope.launch {
            try {
                for (attempt in 1..maxAttempts) {
                    val current = _state.value
                    if (current !is State.EnrollmentRequested || current.deliveryId != deliveryId) {
                        return@launch
                    }
                    when (val res = api.mdmStatus(deliveryId)) {
                        is ApiResult.Ok -> {
                            onMdmStatus(deliveryId, res.value)
                            if (res.value != MdmStatus.TOKEN_REQUESTED) {
                                return@launch
                            }
                        }
                        is ApiResult.Failure -> {
                            fail(res.message, State.EnrollmentRequested(deliveryId))
                            return@launch
                        }
                    }
                    if (attempt < maxAttempts) {
                        delay(intervalMs)
                    }
                }
                _mdmPollTimedOut.value = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Erreur réseau pendant la vérification MDM.", State.EnrollmentRequested(deliveryId))
            } finally {
                _mdmPolling.value = false
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        _mdmPolling.value = false
    }

    private fun onMdmStatus(deliveryId: String, status: MdmStatus) {
        when (status) {
            MdmStatus.ENROLLED -> {
                _mdmPollTimedOut.value = false
                _state.value = State.Enrolled(deliveryId)
            }
            MdmStatus.TOKEN_REQUESTED -> _state.value = State.EnrollmentRequested(deliveryId)
            MdmStatus.FAILED ->
                fail("L'enrôlement du téléphone a échoué. Relancez la demande d'enrôlement.", State.ProofRecorded(deliveryId))
            MdmStatus.NOT_STARTED ->
                fail("L'enrôlement n'a pas été demandé.", State.ProofRecorded(deliveryId))
        }
    }

    fun completeDelivery(deliveryId: String) {
        stopPolling()
        val previous = State.Enrolled(deliveryId)
        run(previous) {
            api.completeDelivery(deliveryId).fold(previous) { _state.value = State.Completed(deliveryId) }
        }
    }

    fun reset() {
        stopPolling()
        _mdmPollTimedOut.value = false
        _state.value = State.Idle
    }

    private fun fail(message: String, previous: State) {
        stopPolling()
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
    private fun run(previous: State, showWorking: Boolean = true, block: suspend () -> Unit) {
        if (showWorking) _state.value = State.Working
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
