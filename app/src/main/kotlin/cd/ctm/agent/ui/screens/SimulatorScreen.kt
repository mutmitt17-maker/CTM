package cd.ctm.agent.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cd.ctm.agent.R
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmChoiceRow
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatCard
import cd.ctm.agent.ui.components.CtmToggleChip
import cd.ctm.agent.ui.components.fmtDay
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.agent.application.ApplicationViewModel
import com.ctm.credit.model.Frequency
import com.ctm.credit.model.SimulationWarning

private fun frequencyLabel(f: Frequency): String = when (f) {
    Frequency.DAILY -> "Par jour"
    Frequency.WEEKLY -> "Par semaine"
    Frequency.MONTHLY -> "Par mois"
}

@Composable
fun SimulatorScreen(
    items: List<CatalogItem>,
    policy: CreditPolicy,
    vm: ApplicationViewModel,
    customerId: String?,
    onQueued: () -> Unit = {},
    onBack: () -> Unit,
) {
    var selectedItemId by rememberSaveable { mutableStateOf(items.firstOrNull()?.id) }
    var down by rememberSaveable { mutableStateOf("") }
    var duration by rememberSaveable { mutableIntStateOf(policy.allowedDurations.firstOrNull() ?: 30) }
    var frequencyWire by rememberSaveable { mutableStateOf(Frequency.MONTHLY.wire) }
    val frequency = Frequency.fromWire(frequencyWire)
    val item = items.firstOrNull { it.id == selectedItemId } ?: items.firstOrNull()
    val state by vm.state.collectAsState()

    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is ApplicationViewModel.State.Queued) onQueued()
    }

    fun form() = ApplicationViewModel.Form(
        customerId = customerId.orEmpty(),
        item = item,
        downPayment = down,
        durationDays = duration,
        frequency = frequency,
    )

    CtmScreenContainer(
        title = stringResource(if (customerId != null) R.string.simulator_application_title else R.string.simulator_title),
        subtitle = stringResource(R.string.simulator_subtitle),
        onBack = onBack,
    ) {
        CtmCard {
            Text(stringResource(R.string.simulator_step_device), style = MaterialTheme.typography.titleMedium)
            items.forEach { it ->
                CtmChoiceRow(
                    selected = item?.id == it.id,
                    label = "${it.brand} ${it.model} — ${usd(it.retailPrice)}",
                    onClick = { selectedItemId = it.id },
                )
            }

            Text(stringResource(R.string.simulator_step_down), style = MaterialTheme.typography.titleMedium)
            CtmField(
                label = stringResource(R.string.simulator_down_label),
                value = down,
                onChange = { down = it },
                placeholder = "0.00",
                keyboard = KeyboardType.Decimal,
                testTag = "simulator_down_input",
            )
            if (item != null) {
                val minPct = item.minDownPaymentPct ?: policy.downPaymentMinPct
                Text(
                    text = "Minimum : ${minPct.toInt()} % du prix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(stringResource(R.string.simulator_step_duration), style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                policy.allowedDurations.forEach { d ->
                    CtmToggleChip(selected = duration == d, label = "$d jours", onClick = { duration = d })
                }
            }

            Text(stringResource(R.string.simulator_step_frequency), style = MaterialTheme.typography.titleMedium)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                Frequency.entries.forEach { f ->
                    CtmToggleChip(selected = frequency == f, label = frequencyLabel(f), onClick = { frequencyWire = f.wire })
                }
            }

            Button(
                onClick = { vm.simulate(form()) },
                modifier = Modifier.fillMaxWidth().testTag("simulator_run_button"),
            ) {
                Text(stringResource(R.string.simulator_run))
            }
        }

        when (val s = state) {
            is ApplicationViewModel.State.Error -> CtmAlertBanner(AlertKind.ERROR, s.message)
            is ApplicationViewModel.State.Submitting -> CtmAlertBanner(AlertKind.INFO, stringResource(R.string.simulator_submitting))
            is ApplicationViewModel.State.Queued -> {
                CtmAlertBanner(
                    AlertKind.OK,
                    stringResource(R.string.simulator_queued_msg),
                )
                Button(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth().testTag("application_done_button"),
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
            is ApplicationViewModel.State.SimulationReady -> {
                val o = s.offer
                s.warnings.forEach { w ->
                    if (w == SimulationWarning.DOWN_PAYMENT_ABOVE_USUAL_RANGE) {
                        CtmAlertBanner(AlertKind.WARN, stringResource(R.string.simulator_warning_above_range))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CtmStatCard(
                        label = "Échéance",
                        value = usd(o.installmentAmount),
                        sub = "${o.installmentCount} fois · ${frequencyLabel(frequency).lowercase()}",
                        modifier = Modifier.weight(1f),
                    )
                    CtmStatCard(
                        label = "Total à rembourser",
                        value = usd(o.totalDue),
                        sub = "Intérêt ${usd(o.interestAmount)}",
                        modifier = Modifier.weight(1f),
                    )
                }
                CtmCard {
                    Text(stringResource(R.string.simulator_offer_detail), style = MaterialTheme.typography.titleMedium)
                    CtmKeyValueRow("Frais de dossier", usd(o.processingFee), mono = true)
                    CtmKeyValueRow("Montant financé", usd(o.financedAmount), mono = true)
                    CtmKeyValueRow("Intérêt", usd(o.interestAmount), mono = true)
                    CtmKeyValueRow("Dernière échéance", usd(o.lastInstallmentAmount), mono = true)
                    CtmKeyValueRow("Première échéance", fmtDay(o.firstDueDate.toString()))
                    CtmKeyValueRow("Fin du crédit", fmtDay(o.endDate.toString()))
                    HorizontalDivider()
                    Text("Échéancier (${o.schedule.size})", style = MaterialTheme.typography.titleSmall)
                    o.schedule.forEach { inst ->
                        CtmKeyValueRow("${inst.number}. ${fmtDay(inst.dueDate.toString())}", usd(inst.amountDue), mono = true)
                    }
                    if (customerId != null) {
                        Button(
                            onClick = { vm.submit(form(), o) },
                            modifier = Modifier.fillMaxWidth().testTag("application_submit_button"),
                        ) {
                            Text(stringResource(R.string.simulator_submit))
                        }
                    }
                }
            }
            is ApplicationViewModel.State.Idle -> Unit
        }
    }
}
