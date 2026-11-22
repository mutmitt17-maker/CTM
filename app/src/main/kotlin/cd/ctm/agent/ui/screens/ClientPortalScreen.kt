package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatCard
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.fmtDateTime
import cd.ctm.agent.ui.components.fmtDay
import cd.ctm.agent.ui.components.usd
import cd.ctm.agent.ui.theme.JetBrainsMono
import com.ctm.credit.client.contract.ContractViewModel
import com.ctm.credit.client.pay.PayInstructionsViewModel

@Composable
fun ClientPortalScreen(
    contractVm: ContractViewModel,
    payVm: PayInstructionsViewModel,
    onBack: () -> Unit,
) {
    val state by contractVm.state.collectAsState()
    val payState by payVm.state.collectAsState()

    LaunchedEffect(Unit) { contractVm.load() }

    CtmScreenContainer(
        title = "Mon crédit",
        subtitle = "Échéancier, paiements et instructions Mobile Money",
        onBack = onBack,
    ) {
        when (val s = state) {
            is ContractViewModel.State.Loading -> CtmLoadingView("Chargement de votre contrat…")
            is ContractViewModel.State.NoContract -> CtmEmptyState("Aucun contrat de crédit associé à ce compte.")
            is ContractViewModel.State.Error -> {
                CtmAlertBanner(AlertKind.ERROR, s.message)
                Button(onClick = { contractVm.refresh() }, modifier = Modifier.fillMaxWidth()) { Text("Réessayer") }
            }
            is ContractViewModel.State.Contracts -> {
                s.contracts.forEach { c ->
                    CtmCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.contractNumber, style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono))
                            CtmStatusBadge(c.status)
                        }
                        CtmKeyValueRow("Total à payer", usd(c.totalPayable), mono = true)
                        CtmKeyValueRow("Du", fmtDay(c.startDate.toString()) + " au " + fmtDay(c.endDate.toString()))
                        Button(onClick = { contractVm.open(c) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Ouvrir ce contrat")
                        }
                    }
                }
            }
            is ContractViewModel.State.Detail -> {
                val d = s.detail
                LaunchedEffect(d.contract.id) { payVm.prepare(d) }

                if (d.isLocked) {
                    CtmAlertBanner(
                        AlertKind.ERROR,
                        "Téléphone verrouillé${d.status.lockReason?.let { " : $it" } ?: ""}. Réglez l'arriéré pour débloquer automatiquement.",
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CtmStatCard(
                        label = "Reste total dû",
                        value = usd(d.status.totalOutstanding),
                        sub = "${d.status.overdueDays} jour(s) de retard",
                        modifier = Modifier.weight(1f),
                    )
                    CtmStatCard(
                        label = "Pénalités",
                        value = usd(d.status.penaltyOutstanding),
                        sub = d.contract.contractNumber,
                        modifier = Modifier.weight(1f),
                    )
                }

                when (val ps = payState) {
                    is PayInstructionsViewModel.State.Ready -> {
                        CtmCard {
                            Text("Instructions de paiement Mobile Money", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            CtmKeyValueRow("Référence obligatoire", ps.reference, mono = true)
                            CtmKeyValueRow("Montant suggéré", usd(ps.suggestedAmount), mono = true)
                            HorizontalDivider()
                            ps.merchants.forEach { m ->
                                CtmKeyValueRow(m.displayName, m.merchantNumber, mono = true)
                            }
                        }
                    }
                    is PayInstructionsViewModel.State.NoMerchant -> {
                        CtmAlertBanner(AlertKind.WARN, "Paiement Mobile Money indisponible pour le moment.")
                    }
                    is PayInstructionsViewModel.State.NothingToPay -> {
                        CtmAlertBanner(AlertKind.OK, "Ce contrat est entièrement soldé.")
                    }
                    else -> Unit
                }

                CtmCard {
                    Text("Échéancier (${d.schedule.size})", style = MaterialTheme.typography.titleMedium)
                    d.schedule.forEach { inst ->
                        CtmKeyValueRow(
                            "${inst.number}. ${fmtDay(inst.dueDate.toString())} (${inst.status.wire})",
                            "${usd(inst.amountPaid)} / ${usd(inst.amountDue)}",
                            mono = true,
                        )
                    }
                }

                CtmCard {
                    Text("Paiements (${d.payments.size})", style = MaterialTheme.typography.titleMedium)
                    if (d.payments.isEmpty()) {
                        Text("Aucun paiement enregistré.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        d.payments.forEach { p ->
                            CtmKeyValueRow(
                                "${p.method} · ${fmtDateTime(p.createdAt.toString())}",
                                "${usd(p.amount)} (${p.status})",
                                mono = true,
                            )
                        }
                    }
                }

                OutlinedButton(onClick = { contractVm.refresh() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Actualiser le contrat")
                }
            }
        }
    }
}
