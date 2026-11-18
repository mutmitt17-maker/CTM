package com.ctm.credit.agent

/**
 * Contrat de l'API utilisée par l'application agent.
 * L'implémentation réelle appelle l'Edge Function via HTTP (OkHttp + Gson).
 * AgentApi.Fake est utilisée dans les tests.
 */
interface AgentApi {

    // ---------------------------------------------------------------- session
    suspend fun login(email: String, password: String): LoginResult
    suspend fun me(): AgentSession
    suspend fun offlineCatalogue(): OfflineCatalogueResult

    // ---------------------------------------------------------------- résultats
    sealed class LoginResult {
        data class Success(val accessToken: String, val refreshToken: String) : LoginResult()
        data class Failure(val message: String) : LoginResult()
    }

    data class OfflineCatalogueResult(
        val items: List<CatalogItem>,
        val policy: CreditPolicy,
        /** Vecteurs de vérification du calculateur (JSON brut renvoyé par calculator_spec()). */
        val calculatorSpecJson: String,
    )
}
