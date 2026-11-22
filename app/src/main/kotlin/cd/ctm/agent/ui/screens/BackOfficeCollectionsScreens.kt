package cd.ctm.agent.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.BadgeTone
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmBadge
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmReasonDialog
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.CtmToggleChip
import cd.ctm.agent.ui.components.fmtDay
import cd.ctm.agent.ui.components.roleLabel
import cd.ctm.agent.ui.components.usd
import cd.ctm.agent.ui.theme.JetBrainsMono
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.http.ApprovalRequestRecord
import com.ctm.credit.http.AssignableAgentRecord
import com.ctm.credit.http.CollectionTaskRecord
import com.ctm.credit.http.ContractLookupResult
import kotlinx.coroutines.launch
import java.math.BigDecimal

@Composable
fun CollectionsScreen(container: AppContainer, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var tasks by remember { mutableStateOf<List<CollectionTaskRecord>>(emptyList()) }
    var agents by remember { mutableStateOf<List<AssignableAgentRecord>>(emptyList()) }
    var completeTarget by remember { mutableStateOf<CollectionTaskRecord?>(null) }
    var assignTarget by remember { mutableStateOf<CollectionTaskRecord?>(null) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchCollections()) {
                is ApiResult.Ok -> {
                    tasks = res.value.first
                    agents = res.value.second
                    error = null
                }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Recouvrement",
        subtitle = "Tâches ouvertes (urgentes en premier)",
        onBack = onBack,
    ) {
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }

        when {
            loading -> CtmLoadingView()
            tasks.isEmpty() -> CtmEmptyState("Aucune tâche de recouvrement ouverte.")
            else -> tasks.forEach { t ->
                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            t.contractNumber ?: "—",
                            style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold),
                        )
                        CtmBadge(
                            if (t.priority == "high") "Urgente" else "Normale",
                            if (t.priority == "high") BadgeTone.BAD else BadgeTone.NEUTRAL,
                        )
                    }
                    t.customerName?.takeIf { it.isNotBlank() }?.let { name ->
                        CtmKeyValueRow("Client", name)
                    }
                    t.customerPhone?.takeIf { it.isNotBlank() }?.let { phone ->
                        CtmKeyValueRow("Téléphone", phone, mono = true)
                    }
                    CtmKeyValueRow("Type", if (t.taskType == "field_visit") "Visite terrain" else "Relance")
                    CtmKeyValueRow("Échéance", fmtDay(t.dueDate))
                    CtmKeyValueRow("Motif", t.reason ?: "—")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("État", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CtmBadge(if (t.assignedTo != null) "Assignée" else "À assigner", if (t.assignedTo != null) BadgeTone.OK else BadgeTone.WARN)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (agents.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { assignTarget = t },
                                modifier = Modifier.weight(1f),
                            ) { Text(if (t.assignedTo != null) "Réassigner" else "Assigner") }
                        }
                        Button(
                            onClick = { completeTarget = t },
                            modifier = Modifier.weight(1f),
                        ) { Text("Clôturer") }
                    }
                }
            }
        }
    }

    completeTarget?.let { t ->
        CtmReasonDialog(
            title = "Clôturer la tâche",
            subtitle = "Contrat ${t.contractNumber ?: "—"}.",
            confirmText = "Clôturer",
            fieldLabel = "Résultat du contact ou de la visite",
            onDismiss = { completeTarget = null },
            onConfirm = { outcome ->
                completeTarget = null
                scope.launch {
                    when (val res = container.backOffice.completeCollectionTask(t.id, outcome)) {
                        is ApiResult.Ok -> load("Tâche clôturée.")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }

    assignTarget?.let { t ->
        var chosenAgentId by remember { mutableStateOf(t.assignedTo ?: agents.firstOrNull()?.id.orEmpty()) }
        var chosenType by remember { mutableStateOf(t.taskType) }
        AlertDialog(
            onDismissRequest = { assignTarget = null },
            title = { Text("Assigner la tâche") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Contrat ${t.contractNumber ?: "—"}.", style = MaterialTheme.typography.bodySmall)
                    Text("Agent", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        agents.forEach { a ->
                            val label = "${roleLabel(a.role)}${if (!a.zone.isNullOrBlank()) " — ${a.zone}" else ""} (${a.id.take(6)})"
                            CtmToggleChip(selected = chosenAgentId == a.id, label = label, onClick = { chosenAgentId = a.id })
                        }
                    }
                    Text("Type de tâche", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CtmToggleChip(selected = chosenType == "followup", label = "Relance", onClick = { chosenType = "followup" })
                        CtmToggleChip(selected = chosenType == "field_visit", label = "Visite terrain", onClick = { chosenType = "field_visit" })
                    }
                }
            },
            dismissButton = { TextButton(onClick = { assignTarget = null }) { Text("Annuler") } },
            confirmButton = {
                Button(
                    enabled = chosenAgentId.isNotBlank(),
                    onClick = {
                        assignTarget = null
                        scope.launch {
                            when (val res = container.backOffice.assignCollectionTask(t.id, chosenAgentId, chosenType)) {
                                is ApiResult.Ok -> load("Tâche assignée.")
                                is ApiResult.Failure -> error = res.message
                            }
                        }
                    },
                ) { Text("Assigner") }
            },
        )
    }
}

@Composable
fun ContractsReliefScreen(container: AppContainer, onBack: () -> Unit) {
    var contractInput by remember { mutableStateOf("CT-") }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<ContractLookupResult?>(null) }

    var waiverAmount by remember { mutableStateOf("") }
    var waiverReason by remember { mutableStateOf("") }
    var waiverResult by remember { mutableStateOf<Pair<AlertKind, String>?>(null) }

    var restDays by remember { mutableStateOf("") }
    var restFreq by remember { mutableStateOf("monthly") }
    var restWaive by remember { mutableStateOf(false) }
    var restReason by remember { mutableStateOf("") }
    var restResult by remember { mutableStateOf<Pair<AlertKind, String>?>(null) }

    val scope = rememberCoroutineScope()

    CtmScreenContainer(
        title = "Remises et restructuration",
        subtitle = "Les demandes sont ensuite décidées dans « Validations »",
        onBack = onBack,
    ) {
        CtmCard {
            CtmField("Numéro de contrat", contractInput, { contractInput = it }, placeholder = "CT-2026-000123")
            error?.let { CtmAlertBanner(AlertKind.ERROR, it) }
            Button(
                enabled = !searching,
                onClick = {
                    error = null
                    found = null
                    waiverResult = null
                    restResult = null
                    val num = contractInput.trim().uppercase()
                    if (!Regex("^CT-\\d{4}-\\d{6}$").matches(num)) {
                        error = "Numéro de contrat invalide (format : CT-2026-000123)."
                        return@Button
                    }
                    searching = true
                    scope.launch {
                        when (val res = container.backOffice.lookupContract(num)) {
                            is ApiResult.Ok -> found = res.value
                            is ApiResult.Failure -> error = res.message
                        }
                        searching = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (searching) "Recherche…" else "Chercher le contrat")
            }
        }

        found?.let { c ->
            CtmCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${c.contractNumber} — ${c.customerName ?: "Client"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    CtmStatusBadge(c.status)
                }
                CtmKeyValueRow("Retard", "${c.overdueDays} jour(s)")
                CtmKeyValueRow("Pénalités dues", usd(c.penaltyOutstandingUsd), mono = true)
                CtmKeyValueRow("Total restant dû", usd(c.totalOutstandingUsd), mono = true)
            }

            CtmCard {
                Text("Demander une remise de pénalités", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                CtmField("Montant à remettre (USD)", waiverAmount, { waiverAmount = it }, keyboard = KeyboardType.Decimal)
                CtmField("Motif", waiverReason, { waiverReason = it })
                waiverResult?.let { (kind, msg) -> CtmAlertBanner(kind, msg) }
                Button(
                    onClick = {
                        waiverResult = null
                        val amt = waiverAmount.trim().replace(',', '.').toBigDecimalOrNull()
                        if (amt == null || amt <= BigDecimal.ZERO) {
                            waiverResult = AlertKind.ERROR to "Montant invalide (positif, 2 décimales au plus)."
                            return@Button
                        }
                        if (amt > c.penaltyOutstandingUsd) {
                            waiverResult = AlertKind.ERROR to "Le montant dépasse les pénalités dues (${usd(c.penaltyOutstandingUsd)})."
                            return@Button
                        }
                        if (waiverReason.trim().isEmpty()) {
                            waiverResult = AlertKind.ERROR to "Un motif est obligatoire."
                            return@Button
                        }
                        scope.launch {
                            when (val res = container.backOffice.requestPenaltyWaiver(c.id, amt, waiverReason.trim())) {
                                is ApiResult.Ok -> {
                                    waiverAmount = ""
                                    waiverReason = ""
                                    waiverResult = AlertKind.OK to "Demande envoyée. Elle doit être validée dans « Validations »."
                                }
                                is ApiResult.Failure -> waiverResult = AlertKind.ERROR to res.message
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Envoyer la demande de remise") }
            }

            CtmCard {
                Text("Demander une restructuration", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                CtmField("Nouvelle durée (jours, 1-365)", restDays, { restDays = it }, keyboard = KeyboardType.Number)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    listOf("daily" to "Par jour", "weekly" to "Par semaine", "monthly" to "Par mois").forEach { (k, l) ->
                        CtmToggleChip(selected = restFreq == k, label = l, onClick = { restFreq = k })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Effacer aussi les pénalités", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = restWaive, onCheckedChange = { restWaive = it })
                }
                CtmField("Motif", restReason, { restReason = it })
                restResult?.let { (kind, msg) -> CtmAlertBanner(kind, msg) }
                Button(
                    onClick = {
                        restResult = null
                        val d = restDays.trim().toIntOrNull()
                        if (d == null || d < 1 || d > 365) {
                            restResult = AlertKind.ERROR to "La durée doit être un entier entre 1 et 365 jours."
                            return@Button
                        }
                        if (restReason.trim().isEmpty()) {
                            restResult = AlertKind.ERROR to "Un motif est obligatoire."
                            return@Button
                        }
                        scope.launch {
                            when (val res = container.backOffice.requestRestructuring(c.id, d, restFreq, restWaive, restReason.trim())) {
                                is ApiResult.Ok -> {
                                    restDays = ""
                                    restReason = ""
                                    restWaive = false
                                    restResult = AlertKind.OK to "Demande envoyée. Elle doit être validée dans « Validations »."
                                }
                                is ApiResult.Failure -> restResult = AlertKind.ERROR to res.message
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Envoyer la restructuration") }
            }
        }
    }
}

@Composable
fun ApprovalsScreen(container: AppContainer, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var requests by remember { mutableStateOf<List<ApprovalRequestRecord>>(emptyList()) }
    var dialogTarget by remember { mutableStateOf<Pair<ApprovalRequestRecord, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchPendingApprovals()) {
                is ApiResult.Ok -> { requests = res.value; error = null }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Validations",
        subtitle = "Double validation des demandes sensibles",
        onBack = onBack,
    ) {
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }
        when {
            loading -> CtmLoadingView()
            requests.isEmpty() -> CtmEmptyState("Aucune demande en attente de validation.")
            else -> requests.forEach { req ->
                CtmCard {
                    Text(req.kind, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    CtmKeyValueRow("Motif", req.reason ?: "—")
                    Text(
                        req.payloadJson,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { dialogTarget = req to true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Approuver") }
                        OutlinedButton(
                            onClick = { dialogTarget = req to false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) { Text("Rejeter") }
                    }
                }
            }
        }
    }

    dialogTarget?.let { (req, approve) ->
        CtmReasonDialog(
            title = if (approve) "Approuver cette demande" else "Rejeter cette demande",
            subtitle = "Indiquez le motif de la décision.",
            confirmText = if (approve) "Approuver" else "Rejeter",
            danger = !approve,
            onDismiss = { dialogTarget = null },
            onConfirm = { reason ->
                dialogTarget = null
                scope.launch {
                    when (val res = container.backOffice.decideApproval(req.id, approve, reason)) {
                        is ApiResult.Ok -> load("Décision enregistrée.")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }
}
