package com.ctm.credit.client

import com.ctm.credit.security.TokenStore
import java.math.BigDecimal
import java.time.Instant
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

val CONTRACT = ContractSummary(
    id = "c-1", contractNumber = "CT-2026-000123", status = "active",
    startDate = LocalDate.of(2026, 1, 15), endDate = LocalDate.of(2026, 4, 15), totalPayable = BigDecimal("300.00"),
)

fun installment(n: Int, due: String, amountDue: String, paid: String, status: InstallmentStatus) =
    Installment(n, LocalDate.parse(due), BigDecimal(amountDue), BigDecimal(paid), status)

fun status(
    deviceState: String? = "unlocked", overdue: Int = 0, penalty: String = "0.00", total: String = "300.00",
) = ContractStatus("active", deviceState, null, null, overdue, BigDecimal(penalty), BigDecimal(total))

/** ClientApi configurable pour les tests. */
class FakeClientApi : ClientApi {
    var otpResult: ClientResult<Unit> = ClientResult.Ok(Unit)
    var verifyResult: ClientResult<ClientTokens> = ClientResult.Ok(ClientTokens("acc", "ref"))
    var contracts: ClientResult<List<ContractSummary>> = ClientResult.Ok(listOf(CONTRACT))
    var scheduleResult: ClientResult<List<Installment>> = ClientResult.Ok(
        listOf(
            installment(2, "2026-03-15", "100.00", "0.00", InstallmentStatus.PENDING),
            installment(1, "2026-02-15", "100.00", "100.00", InstallmentStatus.PAID),
            installment(3, "2026-04-15", "100.00", "0.00", InstallmentStatus.PENDING),
        ),
    )
    var paymentsResult: ClientResult<List<PaymentRecord>> = ClientResult.Ok(
        listOf(
            PaymentRecord("p-1", BigDecimal("100.00"), "mpesa", "success", Instant.parse("2026-02-14T10:00:00Z")),
            PaymentRecord("p-2", BigDecimal("20.00"), "cash", "success", Instant.parse("2026-02-20T10:00:00Z")),
        ),
    )
    var statusResult: ClientResult<ContractStatus> = ClientResult.Ok(status())
    var merchantsResult: ClientResult<List<MerchantAccount>> = ClientResult.Ok(
        listOf(
            MerchantAccount("mpesa", "+243810000001", "CTM M-Pesa", true),
            MerchantAccount("airtel_money", "+243990000002", "CTM Airtel", false),
        ),
    )
    var throwOnContracts = false

    val otpRequests = mutableListOf<String>()
    val verifyRequests = mutableListOf<Pair<String, String>>()

    override suspend fun requestOtp(phone: String): ClientResult<Unit> { otpRequests += phone; return otpResult }
    override suspend fun verifyOtp(phone: String, code: String): ClientResult<ClientTokens> {
        verifyRequests += phone to code
        return verifyResult
    }
    override suspend fun myContracts(): ClientResult<List<ContractSummary>> {
        if (throwOnContracts) throw java.io.IOException("Pas de réseau")
        return contracts
    }
    override suspend fun schedule(contractId: String) = scheduleResult
    override suspend fun payments(contractId: String) = paymentsResult
    override suspend fun contractStatus(contractId: String) = statusResult
    override suspend fun merchantAccounts() = merchantsResult
}
