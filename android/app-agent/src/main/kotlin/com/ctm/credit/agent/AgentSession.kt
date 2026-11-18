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
)

/** Politique de crédit utilisée pour la simulation locale. */
data class CreditPolicy(
    val processingFeeRate: Double,
    val interestRatePer30Days: Double,
    val penaltyRatePerDay: Double,
    val penaltyCapRate: Double,
    val graceDays: Int,
    val lockAfterDaysOverdue: Int,
    val autoApproveMinScore: Int,
    val reviewMinScore: Int,
    val downPaymentMinPct: Double,
    val downPaymentMaxPct: Double,
    val allowedDurations: List<Int>,
)
