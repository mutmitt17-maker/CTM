package com.ctm.credit.agent.kyc

import com.ctm.credit.agent.Json
import com.ctm.credit.agent.parseMoney
import com.ctm.credit.agent.queuedItem
import com.ctm.credit.model.CongoPhone
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * ViewModel du dossier d'identité client (KYC + personne à contacter/garant + consentements).
 *
 * Les trois actions (`kyc.submit`, `emergency_contact.add`, `consent.record`) partagent le même
 * `entityKey = customerId` afin d'être envoyées dans l'ordre après `customer.create`.
 *
 * La biométrie (vivacité et correspondance visage/pièce) est calculée côté serveur par
 * `worker-biometrics` à partir des photos envoyées dans le bucket privé `kyc` : le téléphone
 * n'envoie jamais `face_match_score` ni `liveness_passed`.
 */
class KycViewModel(
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
) {
    enum class IdDocumentType(val wire: String, val label: String) {
        NATIONAL_ID("national_id", "Carte d'identité nationale"),
        VOTER_CARD("voter_card", "Carte d'électeur"),
        PASSPORT("passport", "Passeport"),
        DRIVER_LICENSE("driver_license", "Permis de conduire"),
        ;

        companion object {
            fun fromWire(value: String): IdDocumentType =
                entries.firstOrNull { it.wire == value } ?: NATIONAL_ID
        }
    }

    enum class Relationship(val wire: String, val label: String) {
        PARENT("parent", "Parent"),
        SPOUSE("spouse", "Conjoint(e)"),
        SIBLING("sibling", "Frère / Sœur"),
        FRIEND("friend", "Ami(e)"),
        COLLEAGUE("colleague", "Collègue"),
        OTHER("other", "Autre"),
        ;

        companion object {
            fun fromWire(value: String): Relationship =
                entries.firstOrNull { it.wire == value } ?: PARENT
        }
    }

    data class Form(
        val customerId: String = "",
        val birthDate: String = "",
        val commune: String = "",
        val neighborhood: String = "",
        val occupation: String = "",
        val incomeSource: String = "",
        val declaredMonthlyIncomeUsd: String = "",
        val idDocumentType: IdDocumentType = IdDocumentType.NATIONAL_ID,
        /** Chemin relatif dans le bucket `kyc`, ex. "<customer_uuid>/<nom_unique>.jpg". */
        val idDocumentPhotoPath: String = "",
        /** Chemin relatif dans le bucket `kyc`, ex. "<customer_uuid>/<nom_unique>.jpg". */
        val selfiePath: String = "",
        val emergencyFullName: String = "",
        val emergencyPhone: String = "",
        val emergencyRelationship: Relationship = Relationship.PARENT,
        val emergencyIsGuarantor: Boolean = false,
        val consentBiometrics: Boolean = false,
        val consentCreditTerms: Boolean = false,
        val consentDeviceLock: Boolean = false,
        val consentTextVersion: String = CONSENT_TEXT_VERSION,
    )

    sealed class State {
        data object Idle : State()
        data object Saving : State()
        data class Queued(val customerId: String) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun submit(form: Form, today: LocalDate = LocalDate.now()) {
        val customerId = form.customerId.trim()
        if (customerId.isBlank()) {
            _state.value = State.Error("Sélectionnez d'abord un client.")
            return
        }
        val birthDate = try {
            LocalDate.parse(form.birthDate.trim())
        } catch (_: DateTimeParseException) {
            _state.value = State.Error("Date de naissance invalide (format attendu : AAAA-MM-JJ).")
            return
        }
        if (birthDate.isAfter(today)) {
            _state.value = State.Error("La date de naissance ne peut pas être dans le futur.")
            return
        }
        if (form.commune.isBlank()) {
            _state.value = State.Error("La commune est obligatoire.")
            return
        }
        if (form.neighborhood.isBlank()) {
            _state.value = State.Error("Le quartier est obligatoire.")
            return
        }
        if (form.occupation.isBlank()) {
            _state.value = State.Error("La profession est obligatoire.")
            return
        }
        if (form.incomeSource.isBlank()) {
            _state.value = State.Error("La source de revenu est obligatoire.")
            return
        }
        val monthlyIncome = parseMoney(form.declaredMonthlyIncomeUsd)
        if (monthlyIncome == null) {
            _state.value = State.Error("Revenu mensuel invalide (montant positif, 2 décimales maximum).")
            return
        }
        val docPath = form.idDocumentPhotoPath.trim()
        if (docPath.isBlank()) {
            _state.value = State.Error("La photo de la pièce d'identité est obligatoire.")
            return
        }
        val selfiePath = form.selfiePath.trim()
        if (selfiePath.isBlank()) {
            _state.value = State.Error("Le selfie du client est obligatoire.")
            return
        }
        if (form.emergencyFullName.isBlank()) {
            _state.value = State.Error("Le nom complet de la personne à contacter est obligatoire.")
            return
        }
        if (!CongoPhone.isValid(form.emergencyPhone)) {
            _state.value = State.Error("Téléphone du contact invalide (format : +243XXXXXXXXX).")
            return
        }
        if (!form.consentBiometrics || !form.consentCreditTerms || !form.consentDeviceLock) {
            _state.value = State.Error("Tous les consentements du client doivent être acceptés.")
            return
        }
        val version = form.consentTextVersion.trim().ifBlank { CONSENT_TEXT_VERSION }

        _state.value = State.Saving
        scope.launch {
            val kycPayload = Json.obj(
                "customer_id" to customerId,
                "birth_date" to birthDate.toString(),
                "commune" to form.commune.trim(),
                "neighborhood" to form.neighborhood.trim(),
                "occupation" to form.occupation.trim(),
                "income_source" to form.incomeSource.trim(),
                "declared_monthly_income_usd" to monthlyIncome,
                "id_document_type" to form.idDocumentType.wire,
                "id_document_photo_path" to docPath,
                "selfie_path" to selfiePath,
            )
            val contactPayload = Json.obj(
                "customer_id" to customerId,
                "full_name" to form.emergencyFullName.trim(),
                "phone_number" to form.emergencyPhone.trim(),
                "relationship" to form.emergencyRelationship.wire,
                "is_guarantor" to form.emergencyIsGuarantor,
            )

            syncEngine.enqueue(queuedItem(SyncAction.KYC_SUBMIT, kycPayload, entityKey = customerId))
            syncEngine.enqueue(queuedItem(SyncAction.EMERGENCY_CONTACT_ADD, contactPayload, entityKey = customerId))
            REQUIRED_CONSENTS.forEach { consentType ->
                val consentPayload = Json.obj(
                    "customer_id" to customerId,
                    "consent_type" to consentType,
                    "granted" to true,
                    "text_version" to version,
                )
                syncEngine.enqueue(queuedItem(SyncAction.CONSENT_RECORD, consentPayload, entityKey = customerId))
            }

            _state.value = State.Queued(customerId)
        }
    }

    fun reset() {
        _state.value = State.Idle
    }

    companion object {
        const val CONSENT_TEXT_VERSION = "v1.0"
        val REQUIRED_CONSENTS = listOf("kyc_biometrics", "credit_terms", "device_lock_mdm")
    }
}
