package com.ctm.credit.agent

/**
 * Données de la session courante de l'agent.
 * Chargées au démarrage depuis l'API (action "me") et mises en cache.
 */
data class AgentSession(
    val userId: String,
    val agencyId: String,
    val agencyName: String,
    val agentName: String,
    val role: String,          // "agent", "supervisor", "system_admin", …
    val status: String,        // "active", "suspended", …
)

/** Catalogue d'appareils téléchargé hors ligne. */
data class CatalogItem(
    val id: String,
    val brand: String,
    val model: String,
    val retailPrice: Double,
    val imageUrl: String?,
    val color: String? = null,
    /** Acompte minimum propre à ce téléphone (en %), déjà combiné avec celui de la politique par le serveur. */
    val minDownPaymentPct: Double? = null,
    /** 4 derniers chiffres de l'IMEI, pour reconnaître le téléphone en stock. */
    val imeiSuffix: String? = null,
)

/** Politique de crédit utilisée pour la simulation locale. */
data class CreditPolicy(
    val processingFeeRate: Double,
    val interestRatePer30Days: Double,
    val penaltyRatePerDay: Double,
    /** Non fournis par le catalogue hors ligne du serveur : inutiles pour la simulation. */
    val penaltyCapRate: Double? = null,
    val graceDays: Int,
    val lockAfterDaysOverdue: Int,
    val autoApproveMinScore: Int? = null,
    val reviewMinScore: Int? = null,
    val downPaymentMinPct: Double,
    val downPaymentMaxPct: Double,
    val allowedDurations: List<Int>,
)
