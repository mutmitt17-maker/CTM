package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import cd.ctm.agent.R
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
    onKyc: (customerId: String) -> Unit,
    onApplication: (customerId: String) -> Unit,
    onBack: () -> Unit,
) {
    var firstName by rememberSaveable { mutableStateOf("") }
    var lastName by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("+243") }
    var nationalId by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var city by rememberSaveable { mutableStateOf("") }
    val state by vm.state.collectAsState()

    CtmScreenContainer(
        title = stringResource(R.string.customer_title),
        subtitle = stringResource(R.string.customer_subtitle),
        onBack = { vm.reset(); onBack() },
    ) {
        when (val s = state) {
            is CustomerViewModel.State.Queued -> {
                CtmAlertBanner(
                    AlertKind.OK,
                    stringResource(R.string.customer_queued_msg),
                )
                Button(
                    onClick = { onKyc(s.customerId) },
                    modifier = Modifier.fillMaxWidth().testTag("complete_kyc_for_customer_button"),
                ) {
                    Text(stringResource(R.string.customer_complete_kyc))
                }
                if (canMakeApplication) {
                    OutlinedButton(
                        onClick = { onApplication(s.customerId) },
                        modifier = Modifier.fillMaxWidth().testTag("create_application_for_customer_button"),
                    ) {
                        Text(stringResource(R.string.customer_create_application))
                    }
                }
                OutlinedButton(
                    onClick = { vm.reset(); onBack() },
                    modifier = Modifier.fillMaxWidth().testTag("customer_done_button"),
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
            else -> {
                CtmCard {
                    CtmField(stringResource(R.string.customer_first_name), firstName, { firstName = it }, testTag = "customer_first_name")
                    CtmField(stringResource(R.string.customer_last_name), lastName, { lastName = it }, testTag = "customer_last_name")
                    CtmField(stringResource(R.string.customer_phone), phone, { phone = it }, keyboard = KeyboardType.Phone, testTag = "customer_phone")
                    CtmField(stringResource(R.string.customer_national_id), nationalId, { nationalId = it }, testTag = "customer_national_id")
                    CtmField(stringResource(R.string.customer_address), address, { address = it }, testTag = "customer_address")
                    CtmField(stringResource(R.string.customer_city), city, { city = it }, testTag = "customer_city")
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
                        Text(stringResource(R.string.customer_submit))
                    }
                }
            }
        }
    }
}
