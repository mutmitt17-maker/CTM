package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.payment.PaymentViewModel
import com.ctm.credit.http.AgentContractSummary
import kotlinx.coroutines.launch

@Composable
fun PaymentScreen(
    container: AppContainer,
    vm: PaymentViewModel,
    initialContractNumber: String? = null,
    onQueued: () -> Unit = {},
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var contract by rememberSaveable(initialContractNumber) {
        mutableStateOf(initialContractNumber?.takeIf { it.isNotBlank() } ?: "CT-")
    }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }

    var lookingUp by remember { mutableStateOf(false) }
    var contractInfo by remember { mutableStateOf<AgentContractSummary?>(null) }
    var lookupMessage by remember { mutableStateOf<String?>(null) }

    val state by vm.state.collectAsState()

    LaunchedEffect(state) {
        if (state is PaymentViewModel.State.Queued) onQueued()
    }

    LaunchedEffect(initialContractNumber) {
        if (!initialContractNumber.isNullOrBlank()) {
            lookingUp = true
            lookupMessage = null
            when (val res = container.agentHttpApi.lookupContractByNumber(initialContractNumber)) {
                is ApiResult.Ok -> contractInfo = res.value
                is ApiResult.Failure -> Unit
            }
            lookingUp = false
        }
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
                    onClick = {
                        vm.reset()
                        contract = "CT-"
                        amount = ""
                        note = ""
                        contractInfo = null
                        lookupMessage = null
                    },
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
                        onChange = { contract = it; contractInfo = null; lookupMessage = null },
                        testTag = "payment_contract_input",
                    )

                    OutlinedButton(
                        onClick = {
                            lookingUp = true
                            lookupMessage = null
                            contractInfo = null
                            scope.launch {
                                when (val res = container.agentHttpApi.lookupContractByNumber(contract)) {
                                    is ApiResult.Ok -> {
                                        contractInfo = res.value
                                        if (res.value == null) {
                                            lookupMessage = "Contrat introuvable sur le serveur pour cette agence."
                                        }
                                    }
                                    is ApiResult.Failure -> lookupMessage = res.message
                                }
                                lookingUp = false
                            }
                        },
                        enabled = !lookingUp && contract.trim().length >= 8,
                        modifier = Modifier.fillMaxWidth().testTag("payment_check_balance_button"),
                    ) {
                        if (lookingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.loading_default))
                        } else {
                            Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.payment_check_balance))
                        }
                    }

                    if (lookupMessage != null) {
                        CtmAlertBanner(AlertKind.WARN, lookupMessage!!)
                    }

                    contractInfo?.let { ct ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = ct.customerName ?: ct.contractNumber,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            CtmStatusBadge(ct.status)
                        }
                        CtmKeyValueRow(stringResource(R.string.payment_outstanding_label), usd(ct.outstandingBalance), mono = true)
                        CtmAlertBanner(
                            AlertKind.INFO,
                            stringResource(R.string.payment_mobile_money_notice, ct.contractNumber),
                        )
                    }

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
