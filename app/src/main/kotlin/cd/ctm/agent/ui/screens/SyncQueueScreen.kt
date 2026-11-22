package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.BadgeTone
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmBadge
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.fmtDateTime
import cd.ctm.agent.ui.theme.JetBrainsMono
import com.ctm.credit.network.SyncItem
import com.ctm.credit.network.SyncMessages
import com.ctm.credit.network.SyncStatus

@Composable
fun SyncQueueScreen(
    container: AppContainer,
    onNeedsLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val items by container.syncItems.collectAsState()
    val pending by container.pending.collectAsState()
    val syncMessage by container.syncMessage.collectAsState()
    val syncRunning by container.syncRunning.collectAsState()
    var editingItem by remember { mutableStateOf<SyncItem?>(null) }

    LaunchedEffect(Unit) { container.refreshQueueState() }

    CtmScreenContainer(
        title = "Synchronisation",
        subtitle = "$pending action(s) en attente d'envoi",
        onBack = onBack,
    ) {
        if (syncMessage.isNotBlank()) {
            CtmAlertBanner(AlertKind.INFO, syncMessage)
        }
        Button(
            onClick = { container.syncNow(onNeedsLogin) },
            enabled = !syncRunning,
            modifier = Modifier.fillMaxWidth().testTag("sync_queue_run_button"),
        ) {
            Text(if (syncRunning) "Synchronisation en cours…" else "Synchroniser maintenant")
        }

        if (items.isEmpty()) {
            CtmEmptyState("Aucune action enregistrée dans la file locale.")
        } else {
            items.forEach { item ->
                val (label, tone) = when (item.status) {
                    SyncStatus.PENDING -> "En attente" to BadgeTone.WARN
                    SyncStatus.SENT -> "Envoi en cours" to BadgeTone.INFO
                    SyncStatus.APPLIED -> "Appliquée" to BadgeTone.OK
                    SyncStatus.CONFLICT -> "Conflit" to BadgeTone.BAD
                    SyncStatus.FAILED -> "Refusée" to BadgeTone.BAD
                }
                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(item.action.wire, style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono))
                        CtmBadge(label, tone)
                    }
                    CtmKeyValueRow("Créée le", fmtDateTime(item.clientCreatedAt.toString()))
                    CtmKeyValueRow("Tentatives", item.attempts.toString())
                    if (!item.entityKey.isNullOrBlank()) {
                        CtmKeyValueRow("Entité", item.entityKey!!, mono = true)
                    }
                    if (!item.errorCode.isNullOrBlank()) {
                        CtmAlertBanner(AlertKind.ERROR, SyncMessages.describe(item.errorCode))
                    }
                    if ((item.status == SyncStatus.FAILED || item.status == SyncStatus.CONFLICT) && item.supersededBy == null) {
                        OutlinedButton(
                            onClick = { editingItem = item },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Corriger et renvoyer (nouvelle clé)")
                        }
                    } else if (item.supersededBy != null) {
                        Text(
                            text = "Remplacée par une action corrigée.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    editingItem?.let { old ->
        var payloadText by remember(old.idempotencyKey) { mutableStateOf(old.payloadJson) }
        AlertDialog(
            onDismissRequest = { editingItem = null },
            title = { Text("Corriger ${old.action.wire}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = SyncMessages.describe(old.errorCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        value = payloadText,
                        onValueChange = { payloadText = it },
                        label = { Text("Contenu JSON corrigé") },
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            dismissButton = { TextButton(onClick = { editingItem = null }) { Text("Annuler") } },
            confirmButton = {
                Button(
                    onClick = {
                        container.correctAndRetry(old, payloadText)
                        editingItem = null
                    },
                    enabled = payloadText.isNotBlank(),
                ) {
                    Text("Remettre en file")
                }
            },
        )
    }
}
