package com.ctm.credit.http

import com.ctm.credit.client.ClientApi
import com.ctm.credit.client.ClientResult
import com.ctm.credit.client.ClientTokens
import com.ctm.credit.client.ContractStatus
import com.ctm.credit.client.ContractSummary
import com.ctm.credit.client.Installment
import com.ctm.credit.client.InstallmentStatus
import com.ctm.credit.client.MerchantAccount
import com.ctm.credit.client.PaymentRecord
import com.ctm.credit.network.ApiException
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Implémentation HTTP réelle de [ClientApi] contre PostgREST et l'Edge Function `api`.
 * Les montants sont lus en [BigDecimal] depuis le texte JSON.
 */
class HttpClientApi(private val http: SupabaseHttp) : ClientApi {

    override suspend fun requestOtp(phone: String): ClientResult<Unit> =
        ClientResult.Failure("La connexion client par OTP nécessite l'activation de la fonction serveur client-auth.")

    override suspend fun verifyOtp(phone: String, code: String): ClientResult<ClientTokens> =
        ClientResult.Failure("La vérification OTP nécessite l'activation de la fonction serveur client-auth.")

    override suspend fun myContracts(): ClientResult<List<ContractSummary>> = attempt {
        val arr = http.rest("loan_contract?select=id,contract_number,status,start_date,end_date,total_payable&order=start_date.desc")
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            ContractSummary(
                id = o.stringOrNull("id").orEmpty(),
                contractNumber = o.stringOrNull("contract_number").orEmpty(),
                status = o.stringOrNull("status").orEmpty(),
                startDate = LocalDate.parse(o.stringOrNull("start_date").orEmpty()),
                endDate = LocalDate.parse(o.stringOrNull("end_date").orEmpty()),
                totalPayable = o.decimal("total_payable"),
            )
        }
    }

    override suspend fun schedule(contractId: String): ClientResult<List<Installment>> = attempt {
        val arr = http.rest("repayment_schedule?contract_id=eq.$contractId&select=installment_number,due_date,amount_due,amount_paid,status&order=installment_number")
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            Installment(
                number = o.get("installment_number")?.asInt ?: 0,
                dueDate = LocalDate.parse(o.stringOrNull("due_date").orEmpty()),
                amountDue = o.decimal("amount_due"),
                amountPaid = o.decimal("amount_paid"),
                status = InstallmentStatus.fromWire(o.stringOrNull("status") ?: "pending"),
            )
        }
    }

    override suspend fun payments(contractId: String): ClientResult<List<PaymentRecord>> = attempt {
        val arr = http.rest("payment?contract_id=eq.$contractId&select=id,amount,payment_method,status,created_at&order=created_at.desc")
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            PaymentRecord(
                id = o.stringOrNull("id").orEmpty(),
                amount = o.decimal("amount"),
                method = o.stringOrNull("payment_method").orEmpty(),
                status = o.stringOrNull("status").orEmpty(),
                createdAt = Instant.parse(o.stringOrNull("created_at").orEmpty()),
            )
        }
    }

    override suspend fun contractStatus(contractId: String): ClientResult<ContractStatus> = attempt {
        val o = http.call("contract_status", JsonObject().apply { addProperty("p_contract", contractId) })
            .asJsonObjectOrNull() ?: error("Statut de contrat illisible.")
        ContractStatus(
            contractStatus = o.stringOrNull("contract_status").orEmpty(),
            deviceState = o.stringOrNull("device_state"),
            statusColor = o.stringOrNull("status_color"),
            lockReason = o.stringOrNull("lock_reason"),
            overdueDays = o.get("overdue_days")?.asInt ?: 0,
            penaltyOutstanding = o.decimal("penalty_outstanding_usd"),
            totalOutstanding = o.decimal("total_outstanding_usd"),
        )
    }

    override suspend fun merchantAccounts(): ClientResult<List<MerchantAccount>> = attempt {
        val arr = http.rest("merchant_account?select=provider,merchant_number,display_name,active")
        arr.mapNotNull { el ->
            val o = el.asJsonObjectOrNull() ?: return@mapNotNull null
            MerchantAccount(
                provider = o.stringOrNull("provider").orEmpty(),
                merchantNumber = o.stringOrNull("merchant_number").orEmpty(),
                displayName = o.stringOrNull("display_name").orEmpty(),
                active = o.get("active")?.asBoolean ?: false,
            )
        }
    }

    private suspend fun <T> attempt(block: () -> T): ClientResult<T> = try {
        ClientResult.Ok(withContext(Dispatchers.IO) { block() })
    } catch (e: ApiException) {
        ClientResult.Failure(ErrorMessages.forException(e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ClientResult.Failure(e.message ?: "Réponse inattendue du serveur.")
    }

    private fun JsonObject.decimal(name: String): BigDecimal {
        val el = get(name)
        return if (el == null || el.isJsonNull) BigDecimal.ZERO else el.asBigDecimal
    }
}
