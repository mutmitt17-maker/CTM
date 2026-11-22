package cd.ctm.agent.ui.screens

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmScreenContainer
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

    CtmScreenContainer(
        title = "Nouveau client",
        subtitle = "Enregistrement hors ligne",
        onBack = { vm.reset(); onBack() },
    ) {
        when (val s = state) {
            is CustomerViewModel.State.Queued -> {
                CtmAlertBanner(
                    AlertKind.OK,
                    "Client enregistré sur le téléphone. Il sera envoyé au serveur à la prochaine synchronisation.",
                )
                if (canMakeApplication) {
                    Button(
                        onClick = { onApplication(s.customerId) },
                        modifier = Modifier.fillMaxWidth().testTag("create_application_for_customer_button"),
                    ) {
                        Text("Créer un dossier de crédit pour ce client")
                    }
                }
                OutlinedButton(
                    onClick = { vm.reset(); onBack() },
                    modifier = Modifier.fillMaxWidth().testTag("customer_done_button"),
                ) {
                    Text("Terminé")
                }
            }
            else -> {
                CtmCard {
                    CtmField("Prénom", firstName, { firstName = it }, testTag = "customer_first_name")
                    CtmField("Nom", lastName, { lastName = it }, testTag = "customer_last_name")
                    CtmField("Téléphone (+243…)", phone, { phone = it }, keyboard = KeyboardType.Phone, testTag = "customer_phone")
                    CtmField("Numéro de pièce d'identité", nationalId, { nationalId = it }, testTag = "customer_national_id")
                    CtmField("Adresse", address, { address = it }, testTag = "customer_address")
                    CtmField("Ville (facultatif)", city, { city = it }, testTag = "customer_city")
                    if (s is CustomerViewModel.State.Error) {
                        CtmAlertBanner(AlertKind.ERROR, s.message)
                    }
                    Button(
                        onClick = {
                            vm.submit(
                                CustomerViewModel.Form(
                                    firstName = firstName,
                                    lastName = lastName,
                                    phone = phone,
                                    nationalId = nationalId,
                                    address = address,
                                    city = city,
                                ),
                            )
                        },
                        enabled = s !is CustomerViewModel.State.Saving,
                        modifier = Modifier.fillMaxWidth().testTag("customer_submit_button"),
                    ) {
                        Text("Enregistrer le client")
                    }
                }
            }
        }
    }
}
