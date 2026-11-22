package cd.ctm.agent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

/**
 * Simulation d'un crédit (calcul sur le téléphone, sans réseau).
 * Si [customerId] est donné, l'agent peut ensuite envoyer le dossier ; sinon c'est une simple simulation.
 */
@Composable
fun SimulatorScreen(
    items: List<CatalogItem>,
    policy: CreditPolicy,
    vm: ApplicationViewModel,
    customerId: String?,
    onBack: () -> Unit,
) {
    var item by remember { mutableStateOf<CatalogItem?>(null) }
    var down by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(policy.allowedDurations.firstOrNull() ?: 30) }
    var frequency by remember { mutableStateOf(Frequency.MONTHLY) }
    val state by vm.state.collectAsState()

    fun form() = ApplicationViewModel.Form(
        customerId = customerId.orEmpty(),
        item = item,
        downPayment = down,
        durationDays = duration,
        frequency = frequency,
    )

    ScreenFrame(if (customerId != null) "Dossier de crédit" else "Simulateur", onBack) {
        SectionTitle("1. Téléphone")
        items.forEach { it ->
            Choice(
                selected = item?.id == it.id,
                label = "${it.brand} ${it.model} — ${usd(it.retailPrice)}",
                onClick = { item = it },
            )
        }

        SectionTitle("2. Acompte")
        Field("Acompte (USD)", down, { down = it }, keyboard = KeyboardType.Decimal)
        val chosen = item
        if (chosen != null) {
            val minPct = chosen.minDownPaymentPct ?: policy.downPaymentMinPct
            Text("Minimum : ${minPct.toInt()} % du prix.")
        }

        SectionTitle("3. Durée")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            policy.allowedDurations.forEach { d ->
                Toggle(selected = duration == d, label = "$d jours", onClick = { duration = d })
            }
        }

        SectionTitle("4. Fréquence de paiement")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Frequency.entries.forEach { f ->
                Toggle(selected = frequency == f, label = frequencyLabel(f), onClick = { frequency = f })
            }
        }

        Button(onClick = { vm.simulate(form()) }, modifier = Modifier.fillMaxWidth()) { Text("Simuler") }

        when (val s = state) {
            is ApplicationViewModel.State.Error -> ErrorText(s.message)
            is ApplicationViewModel.State.Submitting -> Text("Enregistrement…")
            is ApplicationViewModel.State.Queued -> {
                Text("Dossier enregistré sur le téléphone. Il sera envoyé à la prochaine synchronisation.")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Terminé") }
            }
            is ApplicationViewModel.State.SimulationReady -> {
                val o = s.offer
                SectionTitle("Résultat")
                Text("Frais de dossier : ${usd(o.processingFee)}")
                Text("Montant financé : ${usd(o.financedAmount)}")
                Text("Intérêt : ${usd(o.interestAmount)}")
                Text("Total à rembourser : ${usd(o.totalDue)}")
                Text("${o.installmentCount} échéance(s) de ${usd(o.installmentAmount)} (la dernière : ${usd(o.lastInstallmentAmount)})")
                Text("Première échéance : ${o.firstDueDate} — fin : ${o.endDate}")
                s.warnings.forEach { w ->
                    if (w == SimulationWarning.DOWN_PAYMENT_ABOVE_USUAL_RANGE) {
                        Text("Attention : l'acompte dépasse la fourchette habituelle.")
                    }
                }
                o.schedule.forEach { i -> Text("${i.number}. ${i.dueDate} — ${usd(i.amountDue)}") }
                if (customerId != null) {
                    Button(onClick = { vm.submit(form(), o) }, modifier = Modifier.fillMaxWidth()) { Text("Envoyer le dossier") }
                }
            }
            is ApplicationViewModel.State.Idle -> Unit
        }
    }
}
