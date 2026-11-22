package com.ctm.credit.client

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Contrat de l'API utilisée par l'application CLIENT.
 *
 * Ce qui EXISTE sur le serveur aujourd'hui :
 *  - lectures PostgREST protégées par la sécurité par lignes (le client ne voit que ses propres données) :
 *    `loan_contract`, `repayment_schedule`, `payment`, `merchant_account` ;
 *  - l'action `contract_status` de l'Edge Function `api` (paramètre `p_contract`).
 * Ce qui MANQUE sur le serveur (proposition, voir CONTRATS_SERVEUR.md) :
 *  - la fonction `client-auth` (envoi et vérification du code OTP).
 *
 * Il n'existe aucune action pour LANCER un paiement depuis l'application : un paiement Mobile Money
 * arrive par le webhook de l'opérateur (référence = numéro de contrat) ou par saisie manuelle du back-office.
 */
interface ClientApi {
    /** `client-auth` / `send_otp` (à créer côté serveur). */
    suspend fun requestOtp(phone: String): ClientResult<Unit>

    /** `client-auth` / `verify_otp` (à créer côté serveur) : renvoie la session Supabase. */
    suspend fun verifyOtp(phone: String, code: String): ClientResult<ClientTokens>

    /** Lecture de `loan_contract` (déjà filtrée par le serveur sur le client connecté). */
    suspend fun myContracts(): ClientResult<List<ContractSummary>>

    /** Lecture de `repayment_schedule` pour un contrat. */
    suspend fun schedule(contractId: String): ClientResult<List<Installment>>

    /** Lecture de `payment` pour un contrat. */
    suspend fun payments(contractId: String): ClientResult<List<PaymentRecord>>

    /** Action `contract_status` de l'Edge Function `api`. */
    suspend fun contractStatus(contractId: String): ClientResult<ContractStatus>

    /** Lecture de `merchant_account` (numéros marchands M-Pesa, Airtel Money, Orange Money). */
    suspend fun merchantAccounts(): ClientResult<List<MerchantAccount>>
}

/** Résultat d'un appel : valeur ou message d'erreur affichable. */
sealed class ClientResult<out T> {
    data class Ok<out T>(val value: T) : ClientResult<T>()
    data class Failure(val message: String) : ClientResult<Nothing>()
}

/** Exécute [onOk] ou [onFailure] selon le résultat. */
internal fun <T> ClientResult<T>.fold(onOk: (T) -> Unit, onFailure: (String) -> Unit) {
    when (this) {
        is ClientResult.Ok -> onOk(value)
        is ClientResult.Failure -> onFailure(message)
    }
}

data class ClientTokens(val accessToken: String, val refreshToken: String)

/** `status` : "active", "closed" ou "breached" (valeurs de l'énumération `contract_status`). */
data class ContractSummary(
    val id: String,
    val contractNumber: String,
    val status: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalPayable: BigDecimal,
)

/** Valeurs de l'énumération `repayment_status` du serveur. */
enum class InstallmentStatus(val wire: String) {
    PENDING("pending"), PARTIAL("partial"), PAID("paid"), OVERDUE("overdue");

    companion object {
        fun fromWire(value: String): InstallmentStatus =
            entries.firstOrNull { it.wire == value } ?: error("repayment_status inconnu : $value")
    }
}

data class Installment(
    val number: Int,
    val dueDate: LocalDate,
    val amountDue: BigDecimal,
    val amountPaid: BigDecimal,
    val status: InstallmentStatus,
) {
    /** Reste à payer sur cette échéance (jamais négatif). */
    val remaining: BigDecimal get() = (amountDue - amountPaid).max(BigDecimal.ZERO)
}

/** `method` : mpesa, airtel_money, orange_money ou cash. `status` : success, failed ou pending. */
data class PaymentRecord(
    val id: String,
    val amount: BigDecimal,
    val method: String,
    val status: String,
    val createdAt: Instant,
)

/** Réponse de l'action `contract_status` (montants en USD). */
data class ContractStatus(
    val contractStatus: String,
    val deviceState: String?,
    val statusColor: String?,
    val lockReason: String?,
    val overdueDays: Int,
    val penaltyOutstanding: BigDecimal,
    val totalOutstanding: BigDecimal,
)

data class MerchantAccount(
    val provider: String,
    val merchantNumber: String,
    val displayName: String,
    val active: Boolean,
)
