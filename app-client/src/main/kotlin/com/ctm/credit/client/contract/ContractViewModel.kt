package com.ctm.credit.client.contract

import com.ctm.credit.client.ClientApi
import com.ctm.credit.client.ClientResult
import com.ctm.credit.client.ContractStatus
import com.ctm.credit.client.ContractSummary
import com.ctm.credit.client.Installment
import com.ctm.credit.client.InstallmentStatus
import com.ctm.credit.client.PaymentRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

/** Tout ce que l'écran de détail d'un contrat affiche. */
data class ContractDetail(
    val contract: ContractSummary,
    val status: ContractStatus,
    /** Triées par numéro d'échéance. */
    val schedule: List<Installment>,
    /** Triés du plus récent au plus ancien. */
    val payments: List<PaymentRecord>,
) {
    /** Première échéance non soldée. */
    val nextInstallment: Installment? get() = schedule.firstOrNull { it.status != InstallmentStatus.PAID }

    /** Reste à payer sur toutes les échéances (hors pénalités). */
    val remainingOnSchedule: BigDecimal
        get() = schedule.fold(BigDecimal.ZERO) { acc, i -> acc + i.remaining }

    val isLocked: Boolean get() = status.deviceState == "locked"
}

/**
 * Consultation des contrats du client : liste, puis détail (statut, échéancier, paiements).
 * Lecture seule : aucune donnée n'est modifiée depuis ce ViewModel.
 */
class ContractViewModel(
    private val api: ClientApi,
    private val scope: CoroutineScope,
) {
    sealed class State {
        object Loading : State()
        object NoContract : State()
        data class Contracts(val contracts: List<ContractSummary>) : State()
        data class Detail(val detail: ContractDetail) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var current: ContractSummary? = null

    /** Charge les contrats ; s'il n'y en a qu'un, ouvre directement son détail. */
    fun load() {
        _state.value = State.Loading
        scope.launch {
            guarded {
                when (val r = api.myContracts()) {
                    is ClientResult.Failure -> _state.value = State.Error(r.message)
                    is ClientResult.Ok -> when (r.value.size) {
                        0 -> _state.value = State.NoContract
                        1 -> openDetail(r.value.single())
                        else -> _state.value = State.Contracts(r.value)
                    }
                }
            }
        }
    }

    fun open(contract: ContractSummary) {
        _state.value = State.Loading
        scope.launch { guarded { openDetail(contract) } }
    }

    /** Recharge le contrat ouvert (par exemple après un paiement). */
    fun refresh() {
        val contract = current
        if (contract == null) {
            load()
        } else {
            open(contract)
        }
    }

    private suspend fun openDetail(contract: ContractSummary) {
        current = contract
        val status = when (val r = api.contractStatus(contract.id)) {
            is ClientResult.Ok -> r.value
            is ClientResult.Failure -> { _state.value = State.Error(r.message); return }
        }
        val schedule = when (val r = api.schedule(contract.id)) {
            is ClientResult.Ok -> r.value
            is ClientResult.Failure -> { _state.value = State.Error(r.message); return }
        }
        val payments = when (val r = api.payments(contract.id)) {
            is ClientResult.Ok -> r.value
            is ClientResult.Failure -> { _state.value = State.Error(r.message); return }
        }
        _state.value = State.Detail(
            ContractDetail(
                contract = contract,
                status = status,
                schedule = schedule.sortedBy { it.number },
                payments = payments.sortedByDescending { it.createdAt },
            ),
        )
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "Erreur réseau. Réessayez.")
        }
    }
}
