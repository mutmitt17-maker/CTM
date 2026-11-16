package com.ctm.credit.network

/** What the screen should offer to the agent for a conflict or a failure. */
enum class Resolution {
    /** Correct the data, then create a NEW action (new key). */
    EDIT_AND_RESUBMIT,

    /** Show the new offer returned by the server to the customer, then submit again. */
    SHOW_NEW_OFFER,

    /** The phone is taken: choose another one. */
    CHOOSE_ANOTHER_DEVICE,

    /** The server has a newer version: reload it and redo the change if it is still needed. */
    REFRESH_AND_REDO,

    /** Nothing the agent can fix: report to the administrator. */
    CONTACT_ADMIN,

    /** The account must be signed out. */
    SIGN_OUT,
}

object SyncMessages {
    private val messages = mapOf(
        "phone_already_registered" to "Ce numéro de téléphone est déjà enregistré pour un autre client.",
        "device_unavailable" to "Ce téléphone n'est plus disponible : un autre agent l'a pris.",
        "stale_update" to "Ce client a été modifié ailleurs après votre modification.",
        "offer_changed" to "L'offre a changé depuis la simulation. Montrez la nouvelle offre au client.",
        "kyc_already_verified" to "Le dossier d'identité est déjà vérifié et ne peut plus être modifié.",
        "duplicate_record" to "Cet enregistrement existe déjà.",
        "unknown_customer" to "Le client n'existe pas encore sur le serveur.",
        "unknown_device" to "Le téléphone est introuvable sur le serveur.",
        "unknown_contract" to "Le contrat est introuvable.",
        "contract_not_active" to "Le contrat n'est pas actif : l'encaissement est impossible.",
        "forbidden" to "Vous n'avez pas le droit d'effectuer cette action.",
        "agent_not_allowed" to "Votre compte est inactif ou verrouillé. Contactez votre responsable.",
        "missing_key_agent_or_action" to "Action incomplète. Contactez l'administrateur.",
        "malformed_item" to "Action mal formée. Contactez l'administrateur.",
        "idempotency_key_reused_with_different_payload" to "Cette action a été modifiée après son envoi. Créez-en une nouvelle.",
        "unknown_action" to "Action inconnue. Mettez l'application à jour.",
    )

    private val resolutions = mapOf(
        "phone_already_registered" to Resolution.EDIT_AND_RESUBMIT,
        "device_unavailable" to Resolution.CHOOSE_ANOTHER_DEVICE,
        "stale_update" to Resolution.REFRESH_AND_REDO,
        "offer_changed" to Resolution.SHOW_NEW_OFFER,
        "kyc_already_verified" to Resolution.CONTACT_ADMIN,
        "duplicate_record" to Resolution.REFRESH_AND_REDO,
        "unknown_customer" to Resolution.EDIT_AND_RESUBMIT,
        "unknown_device" to Resolution.EDIT_AND_RESUBMIT,
        "unknown_contract" to Resolution.EDIT_AND_RESUBMIT,
        "contract_not_active" to Resolution.CONTACT_ADMIN,
        "forbidden" to Resolution.CONTACT_ADMIN,
        "agent_not_allowed" to Resolution.SIGN_OUT,
        "missing_key_agent_or_action" to Resolution.CONTACT_ADMIN,
        "malformed_item" to Resolution.CONTACT_ADMIN,
        "idempotency_key_reused_with_different_payload" to Resolution.EDIT_AND_RESUBMIT,
        "unknown_action" to Resolution.CONTACT_ADMIN,
    )

    val knownCodes: Set<String> get() = messages.keys

    fun describe(code: String?): String =
        messages[code] ?: "Erreur inattendue${code?.let { " ($it)" } ?: ""}. Contactez l'administrateur."

    fun resolutionFor(code: String?): Resolution = resolutions[code] ?: Resolution.CONTACT_ADMIN
}
