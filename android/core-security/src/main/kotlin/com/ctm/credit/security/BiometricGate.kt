package com.ctm.credit.security

/**
 * Déverrouillage biométrique (empreinte digitale ou visage Android).
 *
 * Utilisé pour confirmer l'identité de l'agent avant :
 *   - une connexion (si l'empreinte est enrôlée),
 *   - une action sensible (approbation d'un dossier, enregistrement d'une preuve).
 *
 * L'interface est volontairement mince : toute la logique de prompt Android
 * (BiometricPrompt, CryptoObject, négociation STRONG/WEAK) est dans l'implémentation
 * du module :app-agent. Ce module ne dépend pas d'Android.
 *
 * FakeBiometricGate permet de tester les flux en simulant succès ou échec.
 */
interface BiometricGate {
    /** Résultat d'une tentative de déverrouillage. */
    sealed class Result {
        /** L'agent a été reconnu. [token] est un jeton opaque à courte durée (30 s) pour signer l'action. */
        data class Success(val token: String) : Result()
        /** L'agent a annulé la demande. */
        object Cancelled : Result()
        /** Échec de reconnaissance (trop de tentatives → [locked] = true). */
        data class Failed(val locked: Boolean = false) : Result()
        /** Biométrie non disponible sur cet appareil ou non configurée. */
        object Unavailable : Result()
    }

    /**
     * Vérifie si la biométrie est disponible et configurée sur cet appareil.
     * Renvoie false si aucune empreinte ou aucun visage n'est enrôlé.
     */
    fun isAvailable(): Boolean

    /**
     * Demande une vérification biométrique.
     * Le titre et le sous-titre s'affichent dans le dialogue système Android.
     * Doit être appelée depuis un coroutine ; la réponse arrive en suspend.
     */
    suspend fun authenticate(title: String, subtitle: String): Result
}
