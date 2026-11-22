package cd.ctm.agent.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmConfirmDialog
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmToggleChip
import cd.ctm.agent.ui.components.fmtDateTime
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.client.MerchantAccount
import com.ctm.credit.http.PendingCashPayment
import kotlinx.coroutines.launch

private val MOMO_PROVIDERS = listOf(
    "mpesa" to "M-Pesa",
    "airtel_money" to "Airtel Money",
    "orange_money" to "Orange Money",
)

@Composable
fun ManualMobileMoneyScreen(container: AppContainer, onBack: () -> Unit) {
    var provider by remember { mutableStateOf("mpesa") }
    var ref by remember { mutableStateOf("") }
    var contract by remember { mutableStateOf("CT-") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Map<String, String>?>(null) }
    val scope = rememberCoroutineScope()

    CtmScreenContainer(
        title = "Paiement Mobile Money",
        subtitle = "Saisie manuelle (Finance et superviseurs)",
        onBack = onBack,
    ) {
        CtmCard {
            Text("Opérateur", style = MaterialTheme.typography.titleSmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                MOMO_PROVIDERS.forEach { (key, label) ->
                    CtmToggleChip(selected = provider == key, label = label, onClick = { provider = key })
                }
            }
            CtmField("Référence de transaction opérateur", ref, { ref = it }, testTag = "momo_ref_input")
            CtmField("Numéro de contrat (CT-2026-000123)", contract, { contract = it }, testTag = "momo_contract_input")
            CtmField("Montant (USD)", amount, { amount = it }, placeholder = "0.00", keyboard = KeyboardType.Decimal, testTag = "momo_amount_input")
            CtmField("Note (facultatif)", note, { note = it }, testTag = "momo_note_input")

            error?.let { CtmAlertBanner(AlertKind.ERROR, it) }

            Button(
                enabled = !busy,
                onClick = {
                    error = null
                    result = null
                    val ext = ref.trim()
                    val ct = contract.trim().uppercase()
                    val amt = amount.trim().replace(',', '.').toBigDecimalOrNull()
                    if (ext.isEmpty()) { error = "La référence opérateur est obligatoire."; return@Button }
                    if (ct.isEmpty()) { error = "Le numéro de contrat est obligatoire."; return@Button }
                    if (amt == null || amt.signum() <= 0) { error = "Montant invalide."; return@Button }
                    busy = true
                    scope.launch {
                        when (val res = container.backOffice.recordManualMobileMoneyPayment(ext, ct, amt, provider, note.trim().ifBlank { null })) {
                            is ApiResult.Ok -> {
                                result = res.value
                                ref = ""
                                amount = ""
                                note = ""
                            }
                            is ApiResult.Failure -> error = res.message
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("momo_submit_button"),
            ) {
                Text(if (busy) "Validation…" else "Valider le paiement")
            }
        }

        result?.let { map ->
            CtmAlertBanner(AlertKind.OK, "Opération réussie. Paiement enregistré au grand livre.")
            if (map.isNotEmpty()) {
                CtmCard {
                    map.forEach { (k, v) ->
                        CtmKeyValueRow(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, v, mono = true)
                    }
                }
            }
        }
    }
}

@Composable
fun CashConfirmScreen(container: AppContainer, myUserId: String, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<PendingCashPayment>>(emptyList()) }
    var confirmItem by remember { mutableStateOf<PendingCashPayment?>(null) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchPendingCashPayments()) {
                is ApiResult.Ok -> { items = res.value; error = null }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Espèces à confirmer",
        subtitle = "La confirmation écrit au grand livre et peut débloquer le téléphone",
        onBack = onBack,
    ) {
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }
        when {
            loading -> CtmLoadingView()
            items.isEmpty() -> CtmEmptyState("Aucun paiement en espèces à confirmer.")
            else -> items.forEach { p ->
                val selfCollected = p.collectedBy == myUserId
                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(p.contractNumber, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(usd(p.amount), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    CtmKeyValueRow("Encaissé le", fmtDateTime(p.createdAt))
                    if (selfCollected) {
                        CtmAlertBanner(AlertKind.INFO, "Vous avez encaissé ce paiement : un autre agent Finance doit confirmer.")
                    } else {
                        Button(
                            onClick = { confirmItem = p },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Confirmer la réception") }
                    }
                }
            }
        }
    }

    confirmItem?.let { p ->
        CtmConfirmDialog(
            title = "Confirmer la réception de ${usd(p.amount)} ?",
            message = "Contrat ${p.contractNumber}. Le paiement sera écrit au grand livre.",
            confirmText = "Confirmer la réception",
            onDismiss = { confirmItem = null },
            onConfirm = {
                confirmItem = null
                scope.launch {
                    when (val res = container.backOffice.confirmCashPayment(p.id)) {
                        is ApiResult.Ok -> load("Paiement de ${usd(p.amount)} confirmé (contrat ${p.contractNumber}).")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }
}

@Composable
fun MerchantsScreen(container: AppContainer, role: String, onBack: () -> Unit) {
    val canEdit = role == "system_admin"
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var accounts by remember { mutableStateOf<Map<String, MerchantAccount>>(emptyMap()) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchMerchantAccounts()) {
                is ApiResult.Ok -> {
                    accounts = res.value.associateBy { it.provider }
                    error = null
                }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Numéros marchands",
        subtitle = "Où le client envoie son paiement Mobile Money",
        onBack = onBack,
    ) {
        if (!canEdit) {
            CtmAlertBanner(AlertKind.INFO, "Lecture seule : seul l'administrateur système peut modifier ces numéros.")
        }
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }

        if (loading) {
            CtmLoadingView()
        } else {
            val hasActive = accounts.values.any { it.active && it.merchantNumber.isNotBlank() }
            if (!hasActive) {
                CtmAlertBanner(AlertKind.WARN, "Aucun numéro actif : l'application client ne pourra pas afficher d'instructions de paiement.")
            }
            MOMO_PROVIDERS.forEach { (key, label) ->
                val cur = accounts[key]
                var number by remember(cur) { mutableStateOf(cur?.merchantNumber.orEmpty()) }
                var name by remember(cur) { mutableStateOf(cur?.displayName ?: "CTM $label") }
                var active by remember(cur) { mutableStateOf(cur?.active ?: true) }
                var localErr by remember { mutableStateOf<String?>(null) }
                var saving by remember { mutableStateOf(false) }

                CtmCard {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    CtmField("Numéro marchand", number, { number = it }, placeholder = "+243…", enabled = canEdit)
                    CtmField("Nom affiché au client", name, { name = it }, enabled = canEdit)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Actif (visible dans l'app client)", style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = active, onCheckedChange = { if (canEdit) active = it }, enabled = canEdit)
                    }
                    localErr?.let { CtmAlertBanner(AlertKind.ERROR, it) }
                    if (canEdit) {
                        Button(
                            enabled = !saving,
                            onClick = {
                                localErr = null
                                val num = number.trim()
                                if (!Regex("^[+0-9 ]{6,30}$").matches(num)) {
                                    localErr = "Numéro invalide : chiffres, espaces et « + » seulement (6 à 30 caractères)."
                                    return@Button
                                }
                                if (name.trim().isEmpty()) {
                                    localErr = "Le nom affiché est obligatoire."
                                    return@Button
                                }
                                saving = true
                                scope.launch {
                                    when (val res = container.backOffice.setMerchantAccount(key, num, name.trim(), active)) {
                                        is ApiResult.Ok -> load("$label : enregistré.")
                                        is ApiResult.Failure -> localErr = res.message
                                    }
                                    saving = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (saving) "Enregistrement…" else "Enregistrer")
                        }
                    }
                }
            }
        }
    }
}
