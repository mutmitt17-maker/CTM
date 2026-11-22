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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import cd.ctm.agent.ui.components.CtmConfirmDialog
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmReasonDialog
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.CtmToggleChip
import cd.ctm.agent.ui.components.fmtDateTime
import cd.ctm.agent.ui.components.usd
import cd.ctm.agent.ui.theme.JetBrainsMono
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.http.AgencyRecord
import com.ctm.credit.http.BrandLockProviderRecord
import com.ctm.credit.http.DeviceRestrictionPolicyRecord
import com.ctm.credit.http.ManagedDeviceRecord
import com.ctm.credit.http.PendingSimRecord
import com.ctm.credit.http.StockItemRecord
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen(container: AppContainer, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf<List<ManagedDeviceRecord>>(emptyList()) }
    var lockTarget by remember { mutableStateOf<ManagedDeviceRecord?>(null) }
    var unlockTarget by remember { mutableStateOf<ManagedDeviceRecord?>(null) }
    var holdTarget by remember { mutableStateOf<ManagedDeviceRecord?>(null) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchManagedDevices()) {
                is ApiResult.Ok -> { devices = res.value; error = null }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Appareils gérés",
        subtitle = "État de verrouillage et actions manuelles (50 derniers)",
        onBack = onBack,
    ) {
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }
        when {
            loading -> CtmLoadingView()
            devices.isEmpty() -> CtmEmptyState("Aucun appareil géré pour le moment.")
            else -> devices.forEach { d ->
                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(d.imei, style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono))
                        CtmStatusBadge(d.lockState)
                    }
                    CtmKeyValueRow("Fournisseur", d.lockProvider ?: "—")
                    CtmKeyValueRow("Raison", d.lockReason ?: "—")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Blocage sécurité", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CtmBadge(if (d.securityHold) "Oui" else "Non", if (d.securityHold) BadgeTone.BAD else BadgeTone.NEUTRAL)
                    }
                    CtmKeyValueRow("Dernière activité", fmtDateTime(d.lastSeenAt))

                    if (d.lockState != "released") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (d.lockState != "locked") {
                                OutlinedButton(
                                    onClick = { lockTarget = d },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Verrouiller") }
                            } else {
                                Button(
                                    onClick = { unlockTarget = d },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Déverrouiller") }
                            }
                            if (d.securityHold) {
                                OutlinedButton(
                                    onClick = { holdTarget = d },
                                    modifier = Modifier.weight(1f),
                                ) { Text("Lever blocage") }
                            }
                        }
                    }
                }
            }
        }
    }

    lockTarget?.let { d ->
        CtmReasonDialog(
            title = "Verrouiller l'appareil",
            subtitle = "IMEI ${d.imei}. Indiquez le motif du verrouillage.",
            confirmText = "Verrouiller",
            danger = true,
            onDismiss = { lockTarget = null },
            onConfirm = { reason ->
                lockTarget = null
                scope.launch {
                    when (val res = container.backOffice.manualLockDevice(d.id, reason)) {
                        is ApiResult.Ok -> load("Appareil ${d.imei} : verrouillage demandé.")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }

    unlockTarget?.let { d ->
        var hoursText by remember { mutableStateOf("24") }
        var reasonText by remember { mutableStateOf("") }
        var localErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { unlockTarget = null },
            title = { Text("Déverrouiller l'appareil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("IMEI ${d.imei}. Durée en heures (nombre entier).", style = MaterialTheme.typography.bodySmall)
                    CtmField("Durée (heures)", hoursText, { hoursText = it }, keyboard = KeyboardType.Number)
                    OutlinedTextField(
                        value = reasonText,
                        onValueChange = { reasonText = it },
                        label = { Text("Motif") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    localErr?.let { CtmAlertBanner(AlertKind.ERROR, it) }
                }
            },
            dismissButton = { TextButton(onClick = { unlockTarget = null }) { Text("Annuler") } },
            confirmButton = {
                Button(
                    onClick = {
                        val h = hoursText.trim().toIntOrNull()
                        if (h == null || h < 1) {
                            localErr = "Durée invalide : entrez un nombre entier d'heures."
                            return@Button
                        }
                        if (reasonText.trim().isEmpty()) {
                            localErr = "Un motif est obligatoire."
                            return@Button
                        }
                        unlockTarget = null
                        scope.launch {
                            when (val res = container.backOffice.manualUnlockDevice(d.id, h, reasonText.trim())) {
                                is ApiResult.Ok -> load("Appareil ${d.imei} : déverrouillé pour $h h.")
                                is ApiResult.Failure -> error = res.message
                            }
                        }
                    },
                ) { Text("Déverrouiller") }
            },
        )
    }

    holdTarget?.let { d ->
        CtmReasonDialog(
            title = "Lever le blocage sécurité",
            subtitle = "IMEI ${d.imei}. Indiquez le motif.",
            confirmText = "Confirmer",
            onDismiss = { holdTarget = null },
            onConfirm = { reason ->
                holdTarget = null
                scope.launch {
                    when (val res = container.backOffice.clearSecurityHold(d.id, reason)) {
                        is ApiResult.Ok -> load("Appareil ${d.imei} : blocage sécurité levé.")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }
}

@Composable
fun SimsScreen(container: AppContainer, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var sims by remember { mutableStateOf<List<PendingSimRecord>>(emptyList()) }
    var dialogTarget by remember { mutableStateOf<Pair<PendingSimRecord, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchPendingSims()) {
                is ApiResult.Ok -> { sims = res.value; error = null }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "SIM à valider",
        subtitle = "Autorisez ou refusez les changements de carte SIM détectés",
        onBack = onBack,
    ) {
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }
        when {
            loading -> CtmLoadingView()
            sims.isEmpty() -> CtmEmptyState("Aucune SIM en attente de validation.")
            else -> sims.forEach { s ->
                CtmCard {
                    CtmKeyValueRow("Appareil (IMEI)", s.imei, mono = true)
                    CtmKeyValueRow("Empreinte SIM", s.simHash.take(12) + "…", mono = true)
                    CtmKeyValueRow("Détectée le", fmtDateTime(s.firstSeenAt))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { dialogTarget = s to true },
                            modifier = Modifier.weight(1f),
                        ) { Text("Autoriser") }
                        OutlinedButton(
                            onClick = { dialogTarget = s to false },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) { Text("Refuser") }
                    }
                }
            }
        }
    }

    dialogTarget?.let { (sim, approve) ->
        CtmReasonDialog(
            title = if (approve) "Autoriser cette SIM" else "Refuser cette SIM",
            subtitle = "Appareil ${sim.imei}. Indiquez le motif de la décision.",
            confirmText = if (approve) "Autoriser" else "Refuser",
            danger = !approve,
            onDismiss = { dialogTarget = null },
            onConfirm = { reason ->
                dialogTarget = null
                scope.launch {
                    when (val res = container.backOffice.resolveSimChange(sim.deviceId, sim.simHash, approve, reason)) {
                        is ApiResult.Ok -> load(if (approve) "SIM autorisée." else "SIM refusée.")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }
}

private val LOCK_PROVIDERS = listOf(
    "knox_guard" to "Samsung Knox Guard",
    "apple_abm" to "Apple (ABM)",
    "android_enterprise" to "Android Enterprise",
)

@Composable
fun DevicePolicyScreen(container: AppContainer, role: String, onBack: () -> Unit) {
    val canEdit = role == "system_admin"
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf<DeviceRestrictionPolicyRecord?>(null) }
    var brands by remember { mutableStateOf<List<BrandLockProviderRecord>>(emptyList()) }
    val bools = remember { mutableStateMapOf<String, Boolean>() }
    val nums = remember { mutableStateMapOf<String, String>() }
    var reason by remember { mutableStateOf("") }
    var newBrand by remember { mutableStateOf("") }
    var selectedProvider by remember { mutableStateOf("knox_guard") }
    val scope = rememberCoroutineScope()

    val boolSpecs = listOf(
        "block_usb_debugging" to "Bloquer le débogage USB",
        "block_developer_mode" to "Bloquer le mode développeur",
        "block_factory_reset" to "Bloquer la réinitialisation usine",
        "block_uninstall" to "Bloquer la désinstallation de l'app",
        "block_unknown_sources" to "Bloquer les sources inconnues",
        "block_root" to "Bloquer les appareils rootés",
        "require_locked_bootloader" to "Exiger un bootloader verrouillé",
        "require_verified_boot" to "Exiger le démarrage vérifié",
        "require_oem_lock" to "Exiger le verrou OEM",
    )
    val numSpecs = listOf(
        "attestation_interval_hours" to "Intervalle d'attestation (heures)",
        "max_attestation_failures" to "Échecs d'attestation tolérés",
        "heartbeat_max_silence_days" to "Silence maximal (jours)",
        "sim_grace_hours" to "Délai grâce nouvelle SIM (heures)",
        "max_manual_unlock_hours" to "Durée max déverrouillage manuel (h)",
    )

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchDevicePolicyAndProviders()) {
                is ApiResult.Ok -> {
                    val (p, b) = res.value
                    policy = p
                    brands = b
                    bools["block_usb_debugging"] = p.blockUsbDebugging
                    bools["block_developer_mode"] = p.blockDeveloperMode
                    bools["block_factory_reset"] = p.blockFactoryReset
                    bools["block_uninstall"] = p.blockUninstall
                    bools["block_unknown_sources"] = p.blockUnknownSources
                    bools["block_root"] = p.blockRoot
                    bools["require_locked_bootloader"] = p.requireLockedBootloader
                    bools["require_verified_boot"] = p.requireVerifiedBoot
                    bools["require_oem_lock"] = p.requireOemLock
                    nums["attestation_interval_hours"] = p.attestationIntervalHours.toString()
                    nums["max_attestation_failures"] = p.maxAttestationFailures.toString()
                    nums["heartbeat_max_silence_days"] = p.heartbeatMaxSilenceDays.toString()
                    nums["sim_grace_hours"] = p.simGraceHours.toString()
                    nums["max_manual_unlock_hours"] = p.maxManualUnlockHours.toString()
                    error = null
                }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Politique des appareils",
        subtitle = "Règles de sécurité et fournisseur de verrouillage",
        onBack = onBack,
    ) {
        if (!canEdit) {
            CtmAlertBanner(AlertKind.INFO, "Lecture seule : seul l'administrateur système peut modifier ces réglages.")
        }
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }

        if (loading) {
            CtmLoadingView()
        } else if (policy != null) {
            val orig = policy!!
            CtmCard {
                Text("Restrictions de sécurité", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                boolSpecs.forEach { (key, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = bools[key] ?: false, onCheckedChange = { if (canEdit) bools[key] = it }, enabled = canEdit)
                    }
                }
                numSpecs.forEach { (key, label) ->
                    CtmField(label, nums[key].orEmpty(), { nums[key] = it }, enabled = canEdit, keyboard = KeyboardType.Number)
                }
                if (canEdit) {
                    CtmField("Motif du changement", reason, { reason = it })
                    Button(
                        onClick = {
                            error = null
                            val origMap = mapOf(
                                "block_usb_debugging" to orig.blockUsbDebugging,
                                "block_developer_mode" to orig.blockDeveloperMode,
                                "block_factory_reset" to orig.blockFactoryReset,
                                "block_uninstall" to orig.blockUninstall,
                                "block_unknown_sources" to orig.blockUnknownSources,
                                "block_root" to orig.blockRoot,
                                "require_locked_bootloader" to orig.requireLockedBootloader,
                                "require_verified_boot" to orig.requireVerifiedBoot,
                                "require_oem_lock" to orig.requireOemLock,
                                "attestation_interval_hours" to orig.attestationIntervalHours,
                                "max_attestation_failures" to orig.maxAttestationFailures,
                                "heartbeat_max_silence_days" to orig.heartbeatMaxSilenceDays,
                                "sim_grace_hours" to orig.simGraceHours,
                                "max_manual_unlock_hours" to orig.maxManualUnlockHours,
                            )
                            val changes = mutableMapOf<String, Any>()
                            boolSpecs.forEach { (k, _) ->
                                val cur = bools[k] ?: false
                                if (cur != origMap[k]) changes[k] = cur
                            }
                            for ((k, label) in numSpecs) {
                                val parsed = nums[k].orEmpty().trim().toIntOrNull()
                                if (parsed == null || parsed < 0) {
                                    error = "$label : entrez un nombre entier positif."
                                    return@Button
                                }
                                if (parsed != origMap[k]) changes[k] = parsed
                            }
                            if (changes.isEmpty()) { error = "Aucun changement à enregistrer."; return@Button }
                            if (reason.trim().isEmpty()) { error = "Un motif est obligatoire."; return@Button }
                            scope.launch {
                                when (val res = container.backOffice.updateDevicePolicy(changes, reason.trim())) {
                                    is ApiResult.Ok -> { reason = ""; load("Politique des appareils enregistrée.") }
                                    is ApiResult.Failure -> error = res.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enregistrer la politique") }
                }
            }

            CtmCard {
                Text("Fournisseur de verrouillage par marque", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (brands.isEmpty()) {
                    Text("Aucun fournisseur défini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    brands.forEach { b ->
                        val label = LOCK_PROVIDERS.firstOrNull { it.first == b.provider }?.second ?: b.provider
                        CtmKeyValueRow(b.brand, label)
                    }
                }
                if (canEdit) {
                    CtmField("Marque (ex. samsung)", newBrand, { newBrand = it })
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        LOCK_PROVIDERS.forEach { (key, label) ->
                            CtmToggleChip(selected = selectedProvider == key, label = label, onClick = { selectedProvider = key })
                        }
                    }
                    Button(
                        onClick = {
                            if (newBrand.trim().isEmpty()) { error = "La marque est obligatoire."; return@Button }
                            scope.launch {
                                when (val res = container.backOffice.setBrandLockProvider(newBrand.trim(), selectedProvider)) {
                                    is ApiResult.Ok -> { newBrand = ""; load("Fournisseur de verrouillage enregistré.") }
                                    is ApiResult.Failure -> error = res.message
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enregistrer le fournisseur") }
                }
            }
        }
    }
}

@Composable
fun StockScreen(container: AppContainer, role: String, onBack: () -> Unit) {
    val canEdit = role == "system_admin"
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<StockItemRecord>>(emptyList()) }
    var agencies by remember { mutableStateOf<List<AgencyRecord>>(emptyList()) }
    val selectedIds = remember { mutableStateMapOf<String, Boolean>() }
    var selectedAgencyId by remember { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchStockAndAgencies()) {
                is ApiResult.Ok -> {
                    items = res.value.first
                    agencies = res.value.second
                    selectedIds.clear()
                    if (selectedAgencyId == null) selectedAgencyId = agencies.firstOrNull()?.id
                    error = null
                }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Stock en magasin",
        subtitle = "Seuls les téléphones en stock peuvent changer d'agence",
        onBack = onBack,
    ) {
        if (!canEdit) {
            CtmAlertBanner(AlertKind.INFO, "Lecture seule : seul l'administrateur système peut affecter le stock.")
        }
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }

        when {
            loading -> CtmLoadingView()
            items.isEmpty() -> CtmEmptyState("Aucun téléphone en stock.")
            else -> {
                if (canEdit && agencies.isNotEmpty()) {
                    CtmCard {
                        Text("Agence de destination", style = MaterialTheme.typography.titleSmall)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                        ) {
                            agencies.forEach { ag ->
                                CtmToggleChip(
                                    selected = selectedAgencyId == ag.id,
                                    label = if (ag.region.isNullOrBlank()) ag.name else "${ag.name} (${ag.region})",
                                    onClick = { selectedAgencyId = ag.id },
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val allSelected = items.all { selectedIds[it.id] == true }
                                    items.forEach { selectedIds[it.id] = !allSelected }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Tout sélectionner") }
                            Button(
                                onClick = {
                                    val ids = items.filter { selectedIds[it.id] == true }.map { it.id }
                                    if (ids.isEmpty()) error = "Sélectionnez au moins un appareil."
                                    else if (selectedAgencyId == null) error = "Aucune agence disponible."
                                    else showConfirm = true
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("Affecter") }
                        }
                    }
                }

                items.forEach { item ->
                    val checked = selectedIds[item.id] == true
                    CtmCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (canEdit) {
                                Checkbox(checked = checked, onCheckedChange = { isChecked -> selectedIds[item.id] = isChecked })
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${item.brand} ${item.model}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(item.imei, style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(usd(item.retailPrice), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        CtmKeyValueRow("Agence actuelle", item.agencyName ?: "—")
                    }
                }
            }
        }
    }

    if (showConfirm) {
        val ids = items.filter { selectedIds[it.id] == true }.map { it.id }
        val ag = agencies.firstOrNull { it.id == selectedAgencyId }
        CtmConfirmDialog(
            title = "Affecter à une agence",
            message = "Affecter ${ids.size} appareil(s) à l'agence « ${ag?.name.orEmpty()} » ?",
            confirmText = "Affecter",
            onDismiss = { showConfirm = false },
            onConfirm = {
                showConfirm = false
                val targetAgency = ag?.id ?: return@CtmConfirmDialog
                scope.launch {
                    when (val res = container.backOffice.assignStockToAgency(ids, targetAgency)) {
                        is ApiResult.Ok -> load("${ids.size} appareil(s) affecté(s).")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }
}
