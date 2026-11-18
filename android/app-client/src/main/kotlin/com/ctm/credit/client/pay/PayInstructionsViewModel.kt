package com.ctm.credit.client.pay

import com.ctm.credit.client.ClientApi
import com.ctm.credit.client.ClientResult
import com.ctm.credit.client.MerchantAccount
import com.ctm.credit.client.contract.ContractDetail
import com.ctm.credit.client.parseMoney
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Instructions de paiement Mobile Money.
 *
 * Le serveur n'a pas d'action pour lancer un paiement depuis l'application : le client paie depuis son
 * téléphone vers le numéro marchand, en indiquant le NUMÉRO DE CONTRAT comme référence ; le paiement est
 * ensuite enregistré par le webhook de l'opérateur (ou par le back-office). Ce ViewModel prépare les
 * informations à afficher et valide le montant que le client compte payer.
 */
class PayInstructionsViewModel(
    private val api: ClientApi,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    sealed class State {
        object Idle : State()
        object Loading : State()
        /** Le contrat est entièrement payé. */
        object NothingToPay : State()
        /** Aucun numéro marchand actif : le paiement Mobile Money n'est pas disponible pour le moment. */
        object NoMerchant : State()
        data class Ready(
            /** À indiquer comme référence du paiement. */
            val reference: String,
            val merchants: List<MerchantAccount>,
            /** Échéances arrivées à terme, non soldées (hors pénalités). */
            val dueNow: BigDecimal,
            val penalty: BigDecimal,
            /** Suggestion = échéances dues + pénalités ; sinon reste de la prochaine échéance. Le client peut la modifier. */
            val suggestedAmount: BigDecimal,
            val totalOutstanding: BigDecimal,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun prepare(detail: ContractDetail) {
        val next = detail.nextInstallment
        if (detail.contract.status == "closed" || next == null) {
            _state.value = State.NothingToPay
            return
        }
        _state.value = State.Loading
        scope.launch {
            try {
                when (val r = api.merchantAccounts()) {
                    is ClientResult.Failure -> _state.value = State.Error(r.message)
                    is ClientResult.Ok -> {
                        val merchants = r.value.filter { it.active }
                        _state.value =
                            if (merchants.isEmpty()) State.NoMerchant else ready(detail, next.remaining, merchants)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Erreur réseau. Réessayez.")
            }
        }
    }

    /** Valide le montant saisi par le client : positif, 2 décimales au plus. Renvoie null si invalide. */
    fun parseAmount(text: String): BigDecimal? =
        parseMoney(text)?.takeIf { it.signum() > 0 }

    private fun ready(detail: ContractDetail, nextRemaining: BigDecimal, merchants: List<MerchantAccount>): State.Ready {
        val now = today()
        val dueNow = detail.schedule
            .filter { it.remaining.signum() > 0 && !it.dueDate.isAfter(now) }
            .fold(BigDecimal.ZERO) { acc, i -> acc + i.remaining }
        val penalty = detail.status.penaltyOutstanding
        return State.Ready(
            reference = detail.contract.contractNumber,
            merchants = merchants,
            dueNow = dueNow,
            penalty = penalty,
            suggestedAmount = if (dueNow.signum() > 0) dueNow + penalty else nextRemaining,
            totalOutstanding = detail.status.totalOutstanding,
        )
    }
}
