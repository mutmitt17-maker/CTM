package com.ctm.credit.agent

import com.ctm.credit.network.*
import com.ctm.credit.security.FakeTokenStore

/** AgentApi configurable pour les tests. */
class FakeAgentApi(
    private val loginResult: AgentApi.LoginResult = AgentApi.LoginResult.Success("tok-access", "tok-refresh"),
    private val session: AgentSession = AgentSession("uid-1", "ag-1", "Kinshasa", "Jean Dupont", "agent", "active"),
    private val catalogue: AgentApi.OfflineCatalogueResult = AgentApi.OfflineCatalogueResult(
        items = listOf(
            CatalogItem("item-1", "Samsung", "A15", 150.0, null),
            CatalogItem("item-2", "Tecno", "Spark 20", 120.0, null),
        ),
        policy = testPolicy(),
        calculatorSpecJson = "{}",
    ),
) : AgentApi {
    override suspend fun login(email: String, password: String) = loginResult
    override suspend fun me() = session
    override suspend fun offlineCatalogue() = catalogue
}

/** SyncStore en mémoire pour les tests. */
class FakeSyncStore : SyncStore {
    val items = mutableListOf<SyncItem>()
    override fun sendable(limit: Int, excludeEntities: Set<String>) =
        items.filter { it.status == SyncStatus.PENDING || it.status == SyncStatus.SENT }
            .filter { it.entityKey == null || it.entityKey !in excludeEntities }
            .take(limit)
    override fun blockedEntities() =
        items.filter { it.status == SyncStatus.FAILED || it.status == SyncStatus.CONFLICT }
            .mapNotNull { it.entityKey }.toSet()
    override fun save(item: SyncItem) { items.add(item) }
}

/** SyncApi qui accepte tout. */
class FakeSyncApi : SyncApi {
    override fun syncBatch(items: List<SyncItem>) =
        items.map { ServerResult(it.idempotencyKey, "applied", null, "{}") }
}

fun testPolicy() = CreditPolicy(
    processingFeeRate     = 3.0,
    interestRatePer30Days = 6.0,
    penaltyRatePerDay     = 0.5,
    penaltyCapRate        = 50.0,
    graceDays             = 3,
    lockAfterDaysOverdue  = 15,
    autoApproveMinScore   = 70,
    reviewMinScore        = 50,
    downPaymentMinPct     = 20.0,
    downPaymentMaxPct     = 60.0,
    allowedDurations      = listOf(30, 60, 90, 120, 180),
)
