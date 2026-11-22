package cd.ctm.agent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.ctm.credit.agent.payment.PaymentViewModel

@Composable
fun PaymentScreen(vm: PaymentViewModel, onBack: () -> Unit) {
    var contract by remember { mutableStateOf("CT-") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    ScreenFrame("Encaisser un paiement", onBack = { vm.reset(); onBack() }) {
        Text("Paiement en espèces reçu du client. Il sera confirmé par la Finance après la synchronisation.")
        when (val s = state) {
            is PaymentViewModel.State.Queued -> {
                Text("Paiement de ${usd(s.amount)} pour le contrat ${s.contractNumber} enregistré sur le téléphone.")
                Button(
                    onClick = { vm.reset(); contract = "CT-"; amount = ""; note = "" },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Nouveau paiement") }
            }
            else -> {
                Field("Numéro de contrat (CT-2026-000123)", contract, { contract = it })
                Field("Montant reçu (USD)", amount, { amount = it }, keyboard = KeyboardType.Decimal)
                Field("Note (facultatif)", note, { note = it })
                if (s is PaymentViewModel.State.Error) ErrorText(s.message)
                Button(
                    onClick = { vm.submit(PaymentViewModel.Form(contractNumber = contract, amount = amount, note = note)) },
                    enabled = s !is PaymentViewModel.State.Saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer le paiement") }
            }
        }
    }
}
