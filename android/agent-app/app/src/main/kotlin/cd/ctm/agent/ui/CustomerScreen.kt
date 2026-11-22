package cd.ctm.agent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.ctm.credit.agent.customer.CustomerViewModel

@Composable
fun CustomerScreen(
    vm: CustomerViewModel,
    canMakeApplication: Boolean,
    onApplication: (customerId: String) -> Unit,
    onBack: () -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("+243") }
    var nationalId by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    ScreenFrame("Nouveau client", onBack = { vm.reset(); onBack() }) {
        when (val s = state) {
            is CustomerViewModel.State.Queued -> {
                Text("Client enregistré sur le téléphone. Il sera envoyé à la prochaine synchronisation.")
                if (canMakeApplication) {
                    Button(onClick = { onApplication(s.customerId) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Créer un dossier de crédit pour ce client")
                    }
                }
                OutlinedButton(onClick = { vm.reset(); onBack() }, modifier = Modifier.fillMaxWidth()) { Text("Terminé") }
            }
            else -> {
                Field("Prénom", firstName, { firstName = it })
                Field("Nom", lastName, { lastName = it })
                Field("Téléphone (+243…)", phone, { phone = it }, keyboard = KeyboardType.Phone)
                Field("Numéro de pièce d'identité", nationalId, { nationalId = it })
                Field("Adresse", address, { address = it })
                Field("Ville (facultatif)", city, { city = it })
                if (s is CustomerViewModel.State.Error) ErrorText(s.message)
                Button(
                    onClick = {
                        vm.submit(
                            CustomerViewModel.Form(
                                firstName = firstName, lastName = lastName, phone = phone,
                                nationalId = nationalId, address = address, city = city,
                            ),
                        )
                    },
                    enabled = s !is CustomerViewModel.State.Saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer") }
            }
        }
    }
}
