package com.ctm.credit.http

import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException

/** Messages affichés à l'agent. Le serveur répond en anglais : on traduit ce qu'on connaît. */
internal object ErrorMessages {
    private val known: List<Pair<Regex, String>> = listOf(
        Regex("only an approved application can be delivered", RegexOption.IGNORE_CASE) to "Seul un dossier approuvé peut être livré.",
        Regex("not allowed to deliver this application", RegexOption.IGNORE_CASE) to "Vous n'avez pas le droit de livrer ce dossier.",
        Regex("person who approved the file cannot hand over", RegexOption.IGNORE_CASE) to "La personne qui a approuvé le dossier ne peut pas remettre le téléphone.",
        Regex("delivery not available for this agent", RegexOption.IGNORE_CASE) to "Cette livraison n'est pas disponible pour vous.",
        Regex("scan and verify the IMEI first", RegexOption.IGNORE_CASE) to "Scannez et vérifiez d'abord l'IMEI.",
        Regex("photo and signature are both required", RegexOption.IGNORE_CASE) to "La photo et la signature de remise sont obligatoires.",
        Regex("application is not in the approved state", RegexOption.IGNORE_CASE) to "Le dossier n'est plus à l'état approuvé.",
        Regex("IMEI not verified", RegexOption.IGNORE_CASE) to "L'IMEI n'est pas vérifié.",
        Regex("photo and signature are missing", RegexOption.IGNORE_CASE) to "La photo et la signature de remise manquent.",
        Regex("MDM enrolment is not confirmed", RegexOption.IGNORE_CASE) to "L'enrôlement du téléphone n'est pas encore confirmé. Réessayez dans un instant.",
        Regex("not allowed to read the catalogue", RegexOption.IGNORE_CASE) to "Vous n'avez pas accès au catalogue.",
    )

    fun forException(e: ApiException): String = when (e.kind) {
        ApiErrorKind.NETWORK -> "Pas de connexion. Réessayez quand le réseau revient."
        ApiErrorKind.UNAUTHORIZED -> "Session expirée. Reconnectez-vous."
        ApiErrorKind.SERVER -> "Le serveur a rencontré un problème. Réessayez dans un instant."
        ApiErrorKind.FORBIDDEN -> translate(e.message) ?: "Action non autorisée pour votre compte."
        ApiErrorKind.CONFLICT, ApiErrorKind.REJECTED -> translate(e.message) ?: (e.message ?: "Demande refusée.")
    }

    private fun translate(message: String?): String? =
        if (message == null) null else known.firstOrNull { it.first.containsMatchIn(message) }?.second

    /** Message de connexion : on ne révèle pas si c'est l'e-mail ou le mot de passe qui est faux. */
    fun forLogin(e: ApiException): String = when (e.kind) {
        ApiErrorKind.NETWORK -> "Pas de connexion. Réessayez quand le réseau revient."
        ApiErrorKind.SERVER -> "Le serveur a rencontré un problème. Réessayez dans un instant."
        else ->
            if (e.message?.contains("not confirmed", ignoreCase = true) == true) "Compte non confirmé : contactez l'administrateur."
            else "Email ou mot de passe incorrect."
    }
}
