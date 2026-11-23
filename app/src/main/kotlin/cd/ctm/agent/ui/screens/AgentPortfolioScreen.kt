package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ai.AiOutcome
import cd.ctm.agent.ai.PortfolioBriefResult
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.BadgeTone
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmBadge
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.fmtDay
import cd.ctm.agent.ui.components.usd
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.http.AgentApplicationSummary
import com.ctm.credit.http.AgentContractSummary
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class PortfolioCustomerRow(
    val id: String,
    val fullName: String,
    val phoneNumber: String?,
    val createdAt: String?,
    val syncStatus: SyncStatus?,
    val hasPendingKyc: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPortfolioScreen(
    container: AppContainer,
    canMakeApplication: Boolean,
    onKyc: (String) -> Unit,
    onApplication: (String) -> Unit,
    onDeliver: () -> Unit,
    onCollectPayment: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var reloadKey by rememberSaveable { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var customers by remember { mutableStateOf<List<PortfolioCustomerRow>>(emptyList()) }
    var applications by remember { mutableStateOf<List<AgentApplicationSummary>>(emptyList()) }
    var contracts by remember { mutableStateOf<List<AgentContractSummary>>(emptyList()) }

    var aiLoading by remember { mutableStateOf(false) }
    var aiBrief by remember { mutableStateOf<PortfolioBriefResult?>(null) }
    var aiError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadKey) {
        loading = true
        errorMessage = null
        val (localCustomers, pendingKycCustomerIds) = withContext(Dispatchers.IO) {
            val locals = container.syncStore.localCustomers(limit = 50)
            val kycPending = container.syncStore.listAll(limit = 100)
                .filter { it.action == SyncAction.KYC_SUBMIT && it.status != SyncStatus.APPLIED && it.supersededBy == null }
                .mapNotNull { it.entityKey }
                .toSet()
            locals to kycPending
        }

        val remoteCustRes = container.agentHttpApi.fetchRecentCustomers(limit = 50)
        val remoteAppRes = container.agentHttpApi.fetchAgentApplications(limit = 50)
        val remoteContractRes = container.agentHttpApi.fetchAgentContracts(limit = 50)

        val mergedCustomers = LinkedHashMap<String, PortfolioCustomerRow>()
        localCustomers.forEach { lc ->
            mergedCustomers[lc.id] = PortfolioCustomerRow(
                id = lc.id,
                fullName = lc.fullName,
                phoneNumber = lc.phoneNumber,
                createdAt = null,
                syncStatus = lc.status,
                hasPendingKyc = lc.id in pendingKycCustomerIds,
            )
        }
        if (remoteCustRes is ApiResult.Ok) {
            remoteCustRes.value.forEach { rc ->
                val existing = mergedCustomers[rc.id]
                mergedCustomers[rc.id] = PortfolioCustomerRow(
                    id = rc.id,
                    fullName = rc.fullName,
                    phoneNumber = rc.phoneNumber,
                    createdAt = rc.createdAt,
                    syncStatus = existing?.syncStatus?.takeIf { it != SyncStatus.APPLIED },
                    hasPendingKyc = rc.id in pendingKycCustomerIds,
                )
            }
        } else if (remoteCustRes is ApiResult.Failure) {
            errorMessage = remoteCustRes.message
        }

        customers = mergedCustomers.values.toList()
        applications = if (remoteAppRes is ApiResult.Ok) {
            val agentId = container.tokenStore.currentAgentId().orEmpty()
            if (agentId.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    container.statusTracker.detectTransitions(agentId, remoteAppRes.value)
                }
            }
            remoteAppRes.value
        } else {
            emptyList()
        }
        contracts = if (remoteContractRes is ApiResult.Ok) remoteContractRes.value else emptyList()
        loading = false
    }

    val q = search.trim().lowercase()

    CtmScreenContainer(
        title = stringResource(R.string.portfolio_title),
        subtitle = stringResource(R.string.portfolio_subtitle),
        onBack = onBack,
        actions = {
            IconButton(
                onClick = { reloadKey++ },
                modifier = Modifier.testTag("portfolio_refresh_button"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
            }
        },
    ) {
        CtmField(
            label = stringResource(R.string.portfolio_search_hint),
            value = search,
            onChange = { search = it },
            testTag = "portfolio_search_input",
        )

        PrimaryTabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("${stringResource(R.string.portfolio_tab_customers)} (${customers.size})", maxLines = 1) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("${stringResource(R.string.portfolio_tab_applications)} (${applications.size})", maxLines = 1) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("${stringResource(R.string.portfolio_tab_contracts)} (${contracts.size})", maxLines = 1) },
            )
        }

        if (errorMessage != null) {
            CtmAlertBanner(AlertKind.WARN, errorMessage!!)
        }

        if (loading) {
            CtmLoadingView(stringResource(R.string.loading_default))
        } else {
            val approvedCount = applications.count { it.status == "approved" }
            val overdueCount = contracts.count { it.status == "defaulted" || it.status == "breached" }
            val pendingLocalCount = customers.count { it.syncStatus != null || it.hasPendingKyc }

            CtmCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.portfolio_ai_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    CtmBadge(
                        label = stringResource(R.string.portfolio_ai_counts, approvedCount, overdueCount, pendingLocalCount),
                        tone = if (approvedCount > 0 || overdueCount > 0) BadgeTone.INFO else BadgeTone.NEUTRAL,
                    )
                }

                if (aiBrief != null) {
                    Text(
                        text = aiBrief!!.headline,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (aiBrief!!.summary.isNotBlank()) {
                        Text(
                            text = aiBrief!!.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    aiBrief!!.items.forEach { item ->
                        val tone = when (item.priority) {
                            "URGENT" -> BadgeTone.BAD
                            "HIGH" -> BadgeTone.WARN
                            else -> BadgeTone.INFO
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            CtmBadge(item.priority, tone)
                        }
                        if (item.reason.isNotBlank()) {
                            Text(
                                text = item.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        when (item.suggestedAction) {
                            "DELIVER" -> OutlinedButton(onClick = onDeliver, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.portfolio_action_deliver))
                            }
                            "COLLECT" -> if (item.targetId.startsWith("CT-")) {
                                OutlinedButton(
                                    onClick = { onCollectPayment(item.targetId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("${stringResource(R.string.portfolio_action_collect)} (${item.targetId})")
                                }
                            }
                            "KYC" -> if (item.targetId.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onKyc(item.targetId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.portfolio_action_kyc))
                                }
                            }
                        }
                    }
                } else if (aiError != null) {
                    CtmAlertBanner(AlertKind.WARN, aiError!!)
                }

                OutlinedButton(
                    onClick = {
                        aiLoading = true
                        aiError = null
                        val snapshot = buildPortfolioSnapshot(customers, applications, contracts)
                        scope.launch {
                            when (val res = container.aiAssistant.prioritizePortfolio(snapshot)) {
                                is AiOutcome.Success -> aiBrief = res.data
                                is AiOutcome.Error -> aiError = res.message
                            }
                            aiLoading = false
                        }
                    },
                    enabled = !aiLoading && (customers.isNotEmpty() || applications.isNotEmpty() || contracts.isNotEmpty()),
                    modifier = Modifier.fillMaxWidth().testTag("portfolio_ai_prioritize_button"),
                ) {
                    if (aiLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.portfolio_ai_loading))
                    } else {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (aiBrief == null) R.string.portfolio_ai_button else R.string.portfolio_ai_refresh_button,
                            ),
                        )
                    }
                }
            }

            when (selectedTab) {
                0 -> {
                    val filtered = customers.filter {
                        q.isBlank() ||
                            it.fullName.lowercase().contains(q) ||
                            it.phoneNumber.orEmpty().lowercase().contains(q)
                    }
                    if (filtered.isEmpty()) {
                        CtmEmptyState(stringResource(R.string.portfolio_empty))
                    } else {
                        filtered.forEach { c ->
                            CtmCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = c.fullName,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    val badge = when {
                                        c.syncStatus == SyncStatus.CONFLICT || c.syncStatus == SyncStatus.FAILED ->
                                            "Conflit sync" to BadgeTone.BAD
                                        c.syncStatus == SyncStatus.PENDING || c.syncStatus == SyncStatus.SENT ->
                                            "Hors ligne" to BadgeTone.WARN
                                        c.hasPendingKyc -> "KYC en file" to BadgeTone.INFO
                                        else -> "Synchronisé" to BadgeTone.OK
                                    }
                                    CtmBadge(badge.first, badge.second)
                                }
                                if (!c.phoneNumber.isNullOrBlank()) {
                                    CtmKeyValueRow("Téléphone", c.phoneNumber, mono = true)
                                }
                                if (!c.createdAt.isNullOrBlank()) {
                                    CtmKeyValueRow("Créé le", fmtDay(c.createdAt))
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedButton(
                                        onClick = { onKyc(c.id) },
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(stringResource(R.string.portfolio_action_kyc), maxLines = 1)
                                    }
                                    if (canMakeApplication) {
                                        Button(
                                            onClick = { onApplication(c.id) },
                                            modifier = Modifier.weight(1f),
                                        ) {
                                            Text(stringResource(R.string.portfolio_action_new_app), maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    val filtered = applications.filter {
                        q.isBlank() ||
                            it.customerName.orEmpty().lowercase().contains(q) ||
                            it.customerPhone.orEmpty().lowercase().contains(q) ||
                            it.deviceLabel.orEmpty().lowercase().contains(q)
                    }
                    if (filtered.isEmpty()) {
                        CtmEmptyState(stringResource(R.string.portfolio_empty))
                    } else {
                        filtered.forEach { app ->
                            CtmCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = app.customerName ?: "Client",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CtmStatusBadge(app.status)
                                }
                                app.deviceLabel?.let { CtmKeyValueRow("Appareil", it) }
                                CtmKeyValueRow("Montant financé", usd(app.financedAmount), mono = true)
                                app.decisionReason?.takeIf { it.isNotBlank() }?.let { reason ->
                                    CtmKeyValueRow("Détail", translateDecisionReason(reason))
                                }
                                app.createdAt?.let { CtmKeyValueRow("Date", fmtDay(it)) }
                                if (app.status == "approved") {
                                    Button(
                                        onClick = onDeliver,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.portfolio_action_deliver))
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    val filtered = contracts.filter {
                        q.isBlank() ||
                            it.contractNumber.lowercase().contains(q) ||
                            it.customerName.orEmpty().lowercase().contains(q)
                    }
                    if (filtered.isEmpty()) {
                        CtmEmptyState(stringResource(R.string.portfolio_empty))
                    } else {
                        filtered.forEach { ct ->
                            CtmCard {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = ct.contractNumber,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    CtmStatusBadge(ct.status)
                                }
                                ct.customerName?.let { CtmKeyValueRow("Client", it) }
                                CtmKeyValueRow("Solde restant", usd(ct.outstandingBalance), mono = true)
                                ct.createdAt?.let { CtmKeyValueRow("Ouvert le", fmtDay(it)) }
                                if (ct.status == "active" || ct.status == "defaulted" || ct.status == "breached") {
                                    Button(
                                        onClick = { onCollectPayment(ct.contractNumber) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text(stringResource(R.string.portfolio_action_collect))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun translateDecisionReason(reason: String): String = when (reason) {
    "awaiting_biometric_verification" -> "En attente biométrie serveur"
    "biometrics_not_verified" -> "Biométrie à vérifier manuellement"
    "auto_approved" -> "Approuvé automatiquement"
    else -> reason
}

private fun buildPortfolioSnapshot(
    customers: List<PortfolioCustomerRow>,
    applications: List<AgentApplicationSummary>,
    contracts: List<AgentContractSummary>,
): String = buildString {
    appendLine("CLIENTS (${customers.size}):")
    customers.take(20).forEach {
        appendLine("- id=${it.id} nom=${it.fullName} sync=${it.syncStatus ?: "synced"} kycEnFile=${it.hasPendingKyc}")
    }
    appendLine("DOSSIERS (${applications.size}):")
    applications.take(20).forEach {
        appendLine("- appId=${it.id} client=${it.customerName ?: "?"} statut=${it.status} montant=${it.financedAmount} motif=${it.decisionReason ?: "-"}")
    }
    appendLine("CONTRATS (${contracts.size}):")
    contracts.take(20).forEach {
        appendLine("- num=${it.contractNumber} client=${it.customerName ?: "?"} statut=${it.status} solde=${it.outstandingBalance}")
    }
}
