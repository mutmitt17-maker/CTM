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

    /** action `record_handover_proof` : [signatureHash] = SHA-256 de la signature, [signerDevice] = empreinte de l'appareil. */
    suspend fun recordHandoverProof(
        deliveryId: String,
        photoPath: String,
        signatureHash: String,
        signerDevice: String,
    ): ApiResult<Unit>

    /** action `complete_delivery`. */
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

/** Réponse de `verify_delivery_imei` : `matched` et, si faux, la raison renvoyée par le serveur. */
data class ImeiCheck(val matched: Boolean, val reason: String? = null)
