package com.ctm.credit.agent

import com.ctm.credit.model.CalculatorSpec

/**
 * Contrat de l'API utilisée par l'application agent.
 * L'implémentation réelle appelle l'Edge Function `api` via HTTP (OkHttp + Gson).
 * Les fakes de test sont dans AgentFakes.kt.
 *
 * Les actions de `sync_batch` (client, dossier, paiement) ne passent PAS par ici : elles sont
 * mises en file dans le SyncEngine. La livraison, elle, est faite d'appels directs en ligne.
 */
interface AgentApi {

    // ---------------------------------------------------------------- session
    suspend fun login(email: String, password: String): LoginResult
    suspend fun me(): AgentSession
    suspend fun offlineCatalogue(): OfflineCatalogueResult

    // ---------------------------------------------------------------- livraison (en ligne)
    /** action `start_delivery` : renvoie l'identifiant de livraison créé par le serveur. */
    suspend fun startDelivery(applicationId: String): ApiResult<String>

    /** action `verify_delivery_imei`. */
    suspend fun verifyDeliveryImei(deliveryId: String, scannedImei: String): ApiResult<ImeiCheck>

    /** Envoi d'un fichier (photo JPEG ou signature PNG) dans le bucket privé `handover`. */
    suspend fun uploadHandoverObject(
        objectPath: String,
        bytes: ByteArray,
        contentType: String = "image/jpeg",
    ): ApiResult<Unit> = ApiResult.Ok(Unit)

    /** action `record_handover_proof` : [signatureHash] = SHA-256 de la signature, [signerDevice] = empreinte de l'appareil. */
    suspend fun recordHandoverProof(
        deliveryId: String,
        photoPath: String,
        signatureHash: String,
        signerDevice: String,
    ): ApiResult<Unit>

    /** action `request_mdm_enrollment` : le serveur prépare l'enrôlement MDM (verrouillage à distance) du téléphone. */
    suspend fun requestMdmEnrollment(deliveryId: String): ApiResult<Unit>

    /** Variante retournant d'éventuelles informations d'enrôlement (code, QR, fournisseur) si le backend les fournit. */
    suspend fun requestMdmEnrollmentWithDetails(deliveryId: String): ApiResult<MdmEnrollmentDetails?> =
        when (val res = requestMdmEnrollment(deliveryId)) {
            is ApiResult.Ok -> ApiResult.Ok(null)
            is ApiResult.Failure -> res
        }

    /**
     * Lecture de `delivery.mdm_status` (REST : l'agent voit ses livraisons). Le statut passe à `enrolled`
     * quand le serveur a confirmé l'enrôlement ; `complete_delivery` est refusé tant que ce n'est pas fait.
     */
    suspend fun mdmStatus(deliveryId: String): ApiResult<MdmStatus>

    /** action `complete_delivery` : refusée tant que l'IMEI, la preuve et l'enrôlement MDM ne sont pas confirmés. */
    suspend fun completeDelivery(deliveryId: String): ApiResult<Unit>

    // ---------------------------------------------------------------- résultats
    sealed class LoginResult {
        data class Success(val accessToken: String, val refreshToken: String) : LoginResult()
        data class Failure(val message: String) : LoginResult()
    }

    data class OfflineCatalogueResult(
        val items: List<CatalogItem>,
        val policy: CreditPolicy,
        /** Vecteurs de vérification du calculateur (`calculator` de offline_catalogue), déjà lus par l'implémentation Android. */
        val calculatorSpec: CalculatorSpec,
    )
}

/** Résultat d'un appel direct : valeur ou message d'erreur affichable. */
sealed class ApiResult<out T> {
    data class Ok<out T>(val value: T) : ApiResult<T>()
    data class Failure(val message: String) : ApiResult<Nothing>()
}

/** Détails facultatifs retournés par `request_mdm_enrollment` en mode MDM live. */
data class MdmEnrollmentDetails(
    val provider: String? = null,
    val enrollmentCode: String? = null,
    val qrPayload: String? = null,
    val instructions: String? = null,
)

/** Réponse de `verify_delivery_imei` : `matched` et, si faux, la raison renvoyée par le serveur. */
data class ImeiCheck(val matched: Boolean, val reason: String? = null)

/** Valeurs de `delivery.mdm_status` du serveur. */
enum class MdmStatus(val wire: String) {
    NOT_STARTED("not_started"), TOKEN_REQUESTED("token_requested"), ENROLLED("enrolled"), FAILED("failed");

    companion object {
        fun fromWire(value: String): MdmStatus =
            entries.firstOrNull { it.wire == value } ?: error("mdm_status inconnu : $value")
    }
}
