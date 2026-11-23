package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.payment.PaymentViewModel

@Composable
fun PaymentScreen(
    vm: PaymentViewModel,
    initialContractNumber: String? = null,
    onQueued: () -> Unit = {},
    onBack: () -> Unit,
) {
    var contract by rememberSaveable(initialContractNumber) {
        mutableStateOf(initialContractNumber?.takeIf { it.isNotBlank() } ?: "CT-")
    }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val state by vm.state.collectAsState()

    androidx.compose.runtime.LaunchedEffect(state) {
        if (state is PaymentViewModel.State.Queued) onQueued()
    }

    CtmScreenContainer(
        title = stringResource(R.string.payment_title),
        subtitle = stringResource(R.string.payment_subtitle),
        onBack = { vm.reset(); onBack() },
    ) {
        when (val s = state) {
            is PaymentViewModel.State.Queued -> {
                CtmAlertBanner(
                    AlertKind.OK,
                    "Paiement de ${usd(s.amount)} pour le contrat ${s.contractNumber} enregistré sur le téléphone.",
                )
                Button(
                    onClick = { vm.reset(); contract = "CT-"; amount = ""; note = "" },
                    modifier = Modifier.fillMaxWidth().testTag("payment_new_button"),
                ) {
                    Text(stringResource(R.string.payment_new))
                }
            }
            else -> {
                CtmCard {
                    CtmField(
                        label = stringResource(R.string.payment_contract_label),
                        value = contract,
                        onChange = { contract = it },
                        testTag = "payment_contract_input",
                    )
                    CtmField(
                        label = stringResource(R.string.payment_amount_label),
                        value = amount,
                        onChange = { amount = it },
                        keyboard = KeyboardType.Decimal,
                        testTag = "payment_amount_input",
                    )
                    CtmField(
                        label = stringResource(R.string.payment_note_label),
                        value = note,
                        onChange = { note = it },
                        testTag = "payment_note_input",
                    )
                    if (s is PaymentViewModel.State.Error) {
                        CtmAlertBanner(AlertKind.ERROR, s.message)
                    }
                    Button(
                        onClick = {
                            vm.submit(
                                PaymentViewModel.Form(
                                    contractNumber = contract,
                                    amount = amount,
                                    note = note,
                                ),
                            )
                        },
                        enabled = s !is PaymentViewModel.State.Saving,
                        modifier = Modifier.fillMaxWidth().testTag("payment_submit_button"),
                    ) {
                        Text(stringResource(R.string.payment_submit))
                    }
                }
            }
        }
    }
}
