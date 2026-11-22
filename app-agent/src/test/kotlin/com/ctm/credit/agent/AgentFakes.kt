package com.ctm.credit.agent

import com.ctm.credit.model.CalculatorSpec
import com.ctm.credit.model.ExpectedOffer
import com.ctm.credit.model.Frequency
import com.ctm.credit.model.LoanCalculator
import com.ctm.credit.model.OfferInput
import com.ctm.credit.model.SpecVector
import com.ctm.credit.network.*
import com.ctm.credit.security.DeviceFingerprint
import com.ctm.credit.security.TokenStore
import java.math.BigDecimal
import java.time.LocalDate

/** TokenStore en mémoire (les fakes de :core-security sont dans ses tests, invisibles depuis ce module). */
class InMemoryTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null
    override fun accessToken() = access
    override fun refreshToken() = refresh
    override fun save(accessToken: String, refreshToken: String) { access = accessToken; refresh = refreshToken }
    override fun clear() { access = null; refresh = null }
}

class ConstantFingerprint(private val value: String = "f".repeat(64)) : DeviceFingerprint {
    override fun get() = value
}

/** Vecteurs de calculateur justes : calculés avec le vrai LoanCalculator. */
fun goodSpec(): CalculatorSpec {
    val input = OfferInput(
        retailPrice = BigDecimal("150.00"),
        downPayment = BigDecimal("30.00"),
        durationDays = 30,
        frequency = Frequency.MONTHLY,
        startDate = LocalDate.of(2026, 1, 15),
        processingFeeRate = BigDecimal("0.0300"),
        interestRatePer30Days = BigDecimal("0.0600"),
    )
    val o = LoanCalculator.compute(input)
    val expected = ExpectedOffer(
        o.processingFee, o.financedAmount, o.interestAmount, o.totalDue, o.installmentCount,
        o.installmentAmount, o.lastInstallmentAmount, o.firstDueDate, o.endDate,
    )
    return CalculatorSpec(LoanCalculator.VERSION, listOf(SpecVector(input, expected)))
}

/** Même spec, mais le serveur annonce un total différent d'un centime : le calculateur local doit être désactivé. */
fun divergentSpec(): CalculatorSpec {
    val good = goodSpec()
    val v = good.vectors.single()
    return good.copy(vectors = listOf(v.copy(expected = v.expected.copy(totalDue = v.expected.totalDue + BigDecimal("0.01")))))
}

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
        calculatorSpec = goodSpec(),
    ),
    private val catalogueFails: Boolean = false,
    private val meFails: Boolean = false,
) : AgentApi {
    var startResult: ApiResult<String> = ApiResult.Ok("deliv-1")
    var imeiResult: ApiResult<ImeiCheck> = ApiResult.Ok(ImeiCheck(matched = true))
    var proofResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var completeResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var enrollResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    /** Réponses successives de mdmStatus ; quand la file est vide, le serveur répond « enrolled ». */
    val mdmStatuses = ArrayDeque<ApiResult<MdmStatus>>()
    var enrollCalls = 0
    var startThrows = false

    val imeiCalls = mutableListOf<String>()
    /** (deliveryId, photoPath, signatureHash, signerDevice) */
    val proofCalls = mutableListOf<List<String>>()

    override suspend fun login(email: String, password: String) = loginResult
    override suspend fun me(): AgentSession {
        if (meFails) throw java.io.IOException("Pas de réseau")
        return session
    }
    override suspend fun offlineCatalogue(): AgentApi.OfflineCatalogueResult {
        if (catalogueFails) throw java.io.IOException("Pas de réseau")
        return catalogue
    }

    override suspend fun startDelivery(applicationId: String): ApiResult<String> {
        if (startThrows) throw java.io.IOException("Pas de réseau")
        return startResult
    }

    override suspend fun verifyDeliveryImei(deliveryId: String, scannedImei: String): ApiResult<ImeiCheck> {
        imeiCalls += scannedImei
        return imeiResult
    }

    override suspend fun recordHandoverProof(
        deliveryId: String, photoPath: String, signatureHash: String, signerDevice: String,
    ): ApiResult<Unit> {
        proofCalls += listOf(deliveryId, photoPath, signatureHash, signerDevice)
        return proofResult
    }

    override suspend fun requestMdmEnrollment(deliveryId: String): ApiResult<Unit> {
        enrollCalls++
        return enrollResult
    }

    override suspend fun mdmStatus(deliveryId: String): ApiResult<MdmStatus> =
        mdmStatuses.removeFirstOrNull() ?: ApiResult.Ok(MdmStatus.ENROLLED)

    override suspend fun completeDelivery(deliveryId: String): ApiResult<Unit> = completeResult
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

/** Politique de test : les taux de frais et d'intérêt sont des fractions (0.03 = 3 %), comme sur le serveur ; les % d'acompte sont des pourcentages (20 = 20 %). */
fun testPolicy() = CreditPolicy(
    processingFeeRate     = 0.03,
    interestRatePer30Days = 0.06,
    penaltyRatePerDay     = 0.005,
    penaltyCapRate        = 0.5,
    graceDays             = 3,
    lockAfterDaysOverdue  = 15,
    autoApproveMinScore   = 70,
    reviewMinScore        = 50,
    downPaymentMinPct     = 20.0,
    downPaymentMaxPct     = 60.0,
    allowedDurations      = listOf(30, 60, 90, 120, 180),
)

/** Noms des champs d'un JSON à plat (suffisant pour vérifier le contrat avec le serveur). */
fun keysOf(json: String): Set<String> =
    Regex("\"([a-z_]+)\"\\s*:").findAll(json).map { it.groupValues[1] }.toSet()
