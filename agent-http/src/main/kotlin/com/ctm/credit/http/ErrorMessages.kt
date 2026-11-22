package com.ctm.credit.http

import com.ctm.credit.network.ApiErrorKind
import com.ctm.credit.network.ApiException

/**
 * Messages affichés à l'utilisateur en français.
 * Regroupe les traductions de l'application agent et du back-office (web/app.js).
 */
object ErrorMessages {
    private val known: List<Pair<Regex, String>> = listOf(
        // Connexion & réinitialisation mot de passe (Supabase Auth)
        Regex("email address .* is invalid", RegexOption.IGNORE_CASE) to
            "Cette adresse de test n'accepte pas l'envoi d'e-mail (domaine fictif ou non routable). Utilisez un vrai e-mail ou réinitialisez depuis Supabase.",
        Regex("for security purposes, you can only request this after", RegexOption.IGNORE_CASE) to
            "Patientez quelques secondes avant de redemander un e-mail.",
        Regex("email rate limit exceeded", RegexOption.IGNORE_CASE) to
            "Limite d'envoi d'e-mails atteinte sur le serveur. Réessayez plus tard.",
        // Livraison & agent
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
        // Politique de crédit
        Regex("credit_policy_interest_ceiling", RegexOption.IGNORE_CASE) to "L'intérêt ne peut pas dépasser 100 % par 30 jours.",
        Regex("credit_policy_fee_ceiling", RegexOption.IGNORE_CASE) to "Les frais de dossier ne peuvent pas dépasser 50 %.",
        Regex("credit_policy_penalty_ceiling", RegexOption.IGNORE_CASE) to "La pénalité ne peut pas dépasser 10 % par jour.",
        Regex("credit_policy_down_range", RegexOption.IGNORE_CASE) to "L'acompte minimum ne peut pas dépasser l'acompte maximum.",
        Regex("credit_policy_score_order", RegexOption.IGNORE_CASE) to "Le score de vérification humaine ne peut pas dépasser le score d'acceptation automatique.",
        Regex("credit_policy_burden_order", RegexOption.IGNORE_CASE) to "Les charges sur le revenu doivent aller de la plus petite (bonne) à la plus grande (maximale).",
        // Back-office (web/app.js)
        Regex("only the system administrator", RegexOption.IGNORE_CASE) to "Seul l'administrateur système peut faire cette action.",
        Regex("only Finance can confirm", RegexOption.IGNORE_CASE) to "Seule la Finance peut confirmer un paiement en espèces.",
        Regex("the collector cannot confirm", RegexOption.IGNORE_CASE) to "La personne qui a encaissé l'argent ne peut pas confirmer son propre paiement.",
        Regex("not a pending cash payment", RegexOption.IGNORE_CASE) to "Ce paiement n'est plus en attente (déjà confirmé ou annulé).",
        Regex("amount exceeds the outstanding balance", RegexOption.IGNORE_CASE) to "Le montant dépasse le solde restant dû du contrat.",
        Regex("application is not waiting for a manual review", RegexOption.IGNORE_CASE) to "Ce dossier n'attend plus de décision (déjà traité).",
        Regex("a reason is required", RegexOption.IGNORE_CASE) to "Un motif est obligatoire.",
        Regex("reviewer cannot be the person who created", RegexOption.IGNORE_CASE) to "Vous ne pouvez pas décider d'un dossier que vous avez créé.",
        Regex("reviewer is not allowed", RegexOption.IGNORE_CASE) to "Votre rôle ou votre limite de montant ne vous permet pas cette décision.",
        Regex("unknown or inactive reviewer", RegexOption.IGNORE_CASE) to "Compte inconnu ou inactif.",
        Regex("not allowed to lock a device", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas de verrouiller un appareil.",
        Regex("not allowed to unlock a device", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas de déverrouiller un appareil.",
        Regex("not allowed to clear a security hold", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas de lever un blocage sécurité.",
        Regex("not allowed to decide on this SIM", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas de décider sur cette SIM.",
        Regex("unknown or released device", RegexOption.IGNORE_CASE) to "Appareil inconnu ou déjà libéré.",
        Regex("this device is on security hold", RegexOption.IGNORE_CASE) to "Cet appareil est en blocage sécurité : levez d'abord le blocage.",
        Regex("unlock duration must be between", RegexOption.IGNORE_CASE) to "Durée de déverrouillage hors limite (voir la politique des appareils).",
        Regex("no pending SIM with this hash", RegexOption.IGNORE_CASE) to "Cette SIM n'est plus en attente.",
        Regex("no changes provided", RegexOption.IGNORE_CASE) to "Aucun changement à enregistrer.",
        Regex("unknown or read-only setting", RegexOption.IGNORE_CASE) to "Réglage inconnu ou non modifiable.",
        Regex("unknown or inactive contract", RegexOption.IGNORE_CASE) to "Contrat inconnu ou inactif.",
        Regex("the amount must be between 0 and the penalties", RegexOption.IGNORE_CASE) to "Le montant doit être compris entre 0 et les pénalités encore dues.",
        Regex("the new duration must be between", RegexOption.IGNORE_CASE) to "La nouvelle durée doit être comprise entre 1 et 365 jours.",
        Regex("not allowed to request a penalty waiver", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas de demander une remise de pénalités.",
        Regex("not allowed to request a restructuring", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas de demander une restructuration.",
        Regex("not allowed to assign collection tasks", RegexOption.IGNORE_CASE) to "Votre rôle ne permet pas d'assigner des tâches de recouvrement.",
        Regex("not allowed to close this task", RegexOption.IGNORE_CASE) to "Vous ne pouvez pas clôturer cette tâche.",
        Regex("the assignee must be", RegexOption.IGNORE_CASE) to "La personne assignée doit être un agent de terrain ou de recouvrement actif.",
        Regex("no open task with this id", RegexOption.IGNORE_CASE) to "Cette tâche n'est plus ouverte.",
        Regex("an outcome is required", RegexOption.IGNORE_CASE) to "Le résultat est obligatoire.",
        Regex("every device must exist and be in stock", RegexOption.IGNORE_CASE) to "Chaque appareil doit exister et être en stock pour changer d'agence.",
        Regex("no devices provided", RegexOption.IGNORE_CASE) to "Sélectionnez au moins un appareil.",
        Regex("unknown agency", RegexOption.IGNORE_CASE) to "Agence inconnue.",
        Regex("violates check constraint", RegexOption.IGNORE_CASE) to "Une valeur est hors des limites autorisées.",
    )

    fun forException(e: ApiException): String = when (e.kind) {
        ApiErrorKind.NETWORK -> "Pas de connexion. Réessayez quand le réseau revient."
        ApiErrorKind.UNAUTHORIZED -> "Session expirée. Reconnectez-vous."
        ApiErrorKind.SERVER -> "Le serveur a rencontré un problème. Réessayez dans un instant."
        ApiErrorKind.FORBIDDEN -> translate(e.message) ?: "Action non autorisée pour votre compte."
        ApiErrorKind.CONFLICT, ApiErrorKind.REJECTED -> translate(e.message) ?: (e.message ?: "Demande refusée.")
    }

    fun translate(message: String?): String? =
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
