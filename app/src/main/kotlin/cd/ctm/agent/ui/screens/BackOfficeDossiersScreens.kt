package cd.ctm.agent.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.testTag
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
import cd.ctm.agent.ui.components.fmtDateTime
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.http.FullCreditPolicy
import com.ctm.credit.http.LoanApplicationSummary
import com.ctm.credit.http.PendingReviewCase
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
fun ApplicationsScreen(container: AppContainer, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<LoanApplicationSummary>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun load() {
        loading = true
        scope.launch {
            when (val res = container.backOffice.fetchApplications()) {
                is ApiResult.Ok -> { items = res.value; error = null }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(title = "Dossiers", subtitle = "Les 50 derniers dossiers de crédit", onBack = onBack) {
        when {
            loading -> CtmLoadingView()
            error != null -> {
                CtmAlertBanner(AlertKind.ERROR, error!!)
                Button(onClick = ::load, modifier = Modifier.fillMaxWidth()) { Text("Réessayer") }
            }
            items.isEmpty() -> CtmEmptyState("Aucun dossier pour le moment.")
            else -> items.forEach { app ->
                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(fmtDateTime(app.createdAt), style = MaterialTheme.typography.titleSmall)
                        CtmStatusBadge(app.status)
                    }
                    CtmKeyValueRow("Prix (USD)", usd(app.totalPrice), mono = true)
                    CtmKeyValueRow("Acompte", usd(app.downPayment), mono = true)
                    CtmKeyValueRow("Financé", usd(app.financedAmount), mono = true)
                    CtmKeyValueRow("Durée", "${app.durationDays} jours")
                }
            }
        }
    }
}

@Composable
fun ReviewsScreen(container: AppContainer, myUserId: String, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var cases by remember { mutableStateOf<List<PendingReviewCase>>(emptyList()) }
    var dialogTarget by remember { mutableStateOf<Pair<PendingReviewCase, Boolean>?>(null) }
    val scope = rememberCoroutineScope()

    fun load(okNotice: String? = null) {
        loading = true
        notice = okNotice
        scope.launch {
            when (val res = container.backOffice.fetchPendingReviews()) {
                is ApiResult.Ok -> { cases = res.value; error = null }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Dossiers à décider",
        subtitle = "Dossiers que le score automatique n'a pas tranchés",
        onBack = onBack,
    ) {
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }
        when {
            loading -> CtmLoadingView()
            cases.isEmpty() -> CtmEmptyState("Aucun dossier en attente de décision.")
            else -> cases.forEach { c ->
                val app = c.application
                val dec = c.decision
                val own = app.createdBy == myUserId
                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = app.customerName ?: "Client",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (dec != null) {
                            CtmBadge("Score ${dec.score ?: "—"}/100", BadgeTone.WARN)
                        }
                    }
                    app.customerPhone?.takeIf { it.isNotBlank() }?.let { phone ->
                        CtmKeyValueRow("Téléphone", phone, mono = true)
                    }
                    app.deviceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                        CtmKeyValueRow("Appareil", label)
                    }
                    CtmKeyValueRow("Prix", usd(app.totalPrice), mono = true)
                    CtmKeyValueRow("Acompte", usd(app.downPayment), mono = true)
                    CtmKeyValueRow("Financé", usd(app.financedAmount), mono = true)
                    CtmKeyValueRow("Durée", "${app.durationDays} jours")
                    if (dec != null) {
                        if (dec.reviewFlags.isNotEmpty()) {
                            CtmAlertBanner(AlertKind.WARN, "À vérifier : ${dec.reviewFlags.joinToString(", ")}")
                        }
                        if (dec.hardRuleFailures.isNotEmpty()) {
                            CtmAlertBanner(AlertKind.ERROR, "Règles non respectées : ${dec.hardRuleFailures.joinToString(", ")}")
                        }
                        dec.reason?.takeIf { it.isNotBlank() }?.let { r ->
                            Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (own) {
                        CtmAlertBanner(AlertKind.WARN, "Vous avez créé ce dossier : vous ne pouvez pas le décider.")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = { dialogTarget = c to true },
                            enabled = !own,
                            modifier = Modifier.weight(1f),
                        ) { Text("Approuver") }
                        OutlinedButton(
                            onClick = { dialogTarget = c to false },
                            enabled = !own,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                        ) { Text("Refuser") }
                    }
                }
            }
        }
    }

    dialogTarget?.let { (caseItem, approve) ->
        CtmReasonDialog(
            title = if (approve) "Approuver ce dossier" else "Refuser ce dossier",
            subtitle = "Un motif est obligatoire.",
            confirmText = if (approve) "Approuver" else "Refuser",
            danger = !approve,
            onDismiss = { dialogTarget = null },
            onConfirm = { reason ->
                dialogTarget = null
                scope.launch {
                    when (val res = container.backOffice.resolveReview(caseItem.application.id, approve, reason)) {
                        is ApiResult.Ok -> load(if (approve) "Dossier approuvé." else "Dossier refusé.")
                        is ApiResult.Failure -> error = res.message
                    }
                }
            },
        )
    }
}

private enum class PolicyKind { PCT, NUM, INT, LIST }

private data class PolicyFieldSpec(
    val key: String,
    val label: String,
    val unit: String,
    val help: String,
    val kind: PolicyKind,
    val max: Double? = null,
)

private val POLICY_SECTIONS = listOf(
    "1. Coûts pour le client" to listOf(
        PolicyFieldSpec("processing_fee_rate", "Frais de dossier", "% du prix", "Payés une seule fois à l'ouverture.", PolicyKind.PCT, 50.0),
        PolicyFieldSpec("interest_rate_per_30_days", "Intérêt", "% par 30 jours", "Coût du crédit par tranche de 30 jours.", PolicyKind.PCT, 100.0),
        PolicyFieldSpec("penalty_rate_per_day", "Pénalité de retard", "% par jour", "Ajoutée chaque jour après la grâce.", PolicyKind.PCT, 10.0),
        PolicyFieldSpec("penalty_cap_rate", "Plafond des pénalités", "% de l'échéance", "Plafond des pénalités sur l'échéance.", PolicyKind.PCT, 100.0),
    ),
    "2. Retards" to listOf(
        PolicyFieldSpec("grace_days", "Jours de grâce", "jours", "Tolérance avant pénalités.", PolicyKind.INT),
        PolicyFieldSpec("lock_after_days_overdue", "Blocage après", "jours de retard", "Au-delà, le téléphone est verrouillé.", PolicyKind.INT),
    ),
    "3. Acceptation" to listOf(
        PolicyFieldSpec("auto_approve_min_score", "Acceptation auto", "sur 100", "Accepté sans révision à partir de ce score.", PolicyKind.INT, 100.0),
        PolicyFieldSpec("review_min_score", "Vérification humaine", "sur 100", "En dessous de ce score, refusé.", PolicyKind.INT, 100.0),
        PolicyFieldSpec("down_payment_min_pct", "Acompte minimum", "% du prix", "Part minimale exigée au départ.", PolicyKind.NUM, 100.0),
        PolicyFieldSpec("down_payment_max_pct", "Acompte maximum", "% du prix", "Part maximale habituelle.", PolicyKind.NUM, 100.0),
        PolicyFieldSpec("allowed_durations", "Durées autorisées", "jours (virgules)", "Ex. : 30, 60, 90, 120, 180", PolicyKind.LIST),
    ),
    "4. Sécurité & Charge" to listOf(
        PolicyFieldSpec("face_match_min", "Correspondance visage min", "%", "Seuil minimum de reconnaissance faciale.", PolicyKind.PCT, 100.0),
        PolicyFieldSpec("ocr_confidence_min", "Lecture pièce ID min", "%", "Certitude OCR minimale.", PolicyKind.PCT, 100.0),
        PolicyFieldSpec("burden_good", "Charge revenu : bonne", "% du revenu", "Meilleure note jusqu'à ce seuil.", PolicyKind.PCT, 100.0),
        PolicyFieldSpec("burden_ok", "Charge revenu : acceptable", "% du revenu", "Note moyenne jusqu'à ce seuil.", PolicyKind.PCT, 100.0),
        PolicyFieldSpec("burden_max", "Charge revenu : maximale", "% du revenu", "Au-delà, note de charge nulle.", PolicyKind.PCT, 100.0),
    ),
)

@Composable
fun CreditPolicyScreen(container: AppContainer, role: String, onBack: () -> Unit) {
    val canEdit = role == "system_admin"
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var policy by remember { mutableStateOf<FullCreditPolicy?>(null) }
    val inputs = remember { mutableStateMapOf<String, String>() }
    var reason by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun policyValue(p: FullCreditPolicy, key: String): Any = when (key) {
        "processing_fee_rate" -> p.processingFeeRate
        "interest_rate_per_30_days" -> p.interestRatePer30Days
        "penalty_rate_per_day" -> p.penaltyRatePerDay
        "penalty_cap_rate" -> p.penaltyCapRate
        "grace_days" -> p.graceDays
        "lock_after_days_overdue" -> p.lockAfterDaysOverdue
        "auto_approve_min_score" -> p.autoApproveMinScore
        "review_min_score" -> p.reviewMinScore
        "down_payment_min_pct" -> p.downPaymentMinPct
        "down_payment_max_pct" -> p.downPaymentMaxPct
        "allowed_durations" -> p.allowedDurations
        "face_match_min" -> p.faceMatchMin
        "ocr_confidence_min" -> p.ocrConfidenceMin
        "burden_good" -> p.burdenGood
        "burden_ok" -> p.burdenOk
        "burden_max" -> p.burdenMax
        else -> ""
    }

    fun load(okMsg: String? = null) {
        loading = true
        notice = okMsg
        scope.launch {
            when (val res = container.backOffice.fetchCreditPolicy()) {
                is ApiResult.Ok -> {
                    val p = res.value
                    policy = p
                    POLICY_SECTIONS.flatMap { it.second }.forEach { f ->
                        val v = policyValue(p, f.key)
                        inputs[f.key] = when (f.kind) {
                            PolicyKind.LIST -> (v as List<*>).joinToString(", ")
                            PolicyKind.PCT -> (v as BigDecimal).multiply(BigDecimal(100)).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                            PolicyKind.NUM -> (v as BigDecimal).stripTrailingZeros().toPlainString()
                            PolicyKind.INT -> v.toString()
                        }
                    }
                    error = null
                }
                is ApiResult.Failure -> error = res.message
            }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    CtmScreenContainer(
        title = "Politique de crédit",
        subtitle = "Règles appliquées aux nouveaux dossiers",
        onBack = onBack,
    ) {
        if (!canEdit) {
            CtmAlertBanner(AlertKind.INFO, "Lecture seule : seul l'administrateur système peut modifier ces règles.")
        }
        notice?.let { CtmAlertBanner(AlertKind.OK, it) }
        error?.let { CtmAlertBanner(AlertKind.ERROR, it) }

        if (loading) {
            CtmLoadingView()
        } else if (policy != null) {
            val current = policy!!
            POLICY_SECTIONS.forEach { (sectionTitle, fields) ->
                CtmCard {
                    Text(sectionTitle, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    fields.forEach { f ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CtmField(
                                label = "${f.label} (${f.unit})",
                                value = inputs[f.key].orEmpty(),
                                onChange = { inputs[f.key] = it },
                                enabled = canEdit,
                                keyboard = if (f.kind == PolicyKind.LIST) KeyboardType.Text else KeyboardType.Decimal,
                            )
                            Text(f.help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (canEdit) {
                CtmCard {
                    CtmField(
                        label = "Motif du changement",
                        value = reason,
                        onChange = { reason = it },
                        placeholder = "Ex. : ajustement de l'intérêt",
                        testTag = "policy_reason_input",
                    )
                    Button(
                        enabled = !saving,
                        onClick = {
                            error = null
                            notice = null
                            val r = reason.trim()
                            if (r.isEmpty()) {
                                error = "Un motif est obligatoire."
                                return@Button
                            }
                            val changes = mutableMapOf<String, Any>()
                            try {
                                for ((_, fields) in POLICY_SECTIONS) {
                                    for (f in fields) {
                                        val raw = inputs[f.key].orEmpty().trim()
                                        val orig = policyValue(current, f.key)
                                        when (f.kind) {
                                            PolicyKind.LIST -> {
                                                val nums = raw.split(Regex("[\\s,;]+")).filter { it.isNotBlank() }.map {
                                                    it.toIntOrNull() ?: error("« ${f.label} » : entiers positifs attendus.")
                                                }.distinct().sorted()
                                                if (nums.isEmpty() || nums.any { it <= 0 }) error("« ${f.label} » : saisissez des durées positives.")
                                                if (nums != orig) changes[f.key] = nums
                                            }
                                            PolicyKind.INT -> {
                                                val n = raw.toIntOrNull() ?: error("« ${f.label} » : nombre entier requis.")
                                                if (n < 0) error("« ${f.label} » : nombre positif requis.")
                                                if (f.max != null && n > f.max) error("« ${f.label} » : ${f.max.toInt()} au maximum.")
                                                if (n != (orig as Int)) changes[f.key] = n
                                            }
                                            PolicyKind.NUM -> {
                                                val n = raw.replace(',', '.').toBigDecimalOrNull() ?: error("« ${f.label} » : nombre requis.")
                                                if (n.signum() < 0) error("« ${f.label} » : nombre positif requis.")
                                                if (f.max != null && n > BigDecimal.valueOf(f.max)) error("« ${f.label} » : ${f.max} au maximum.")
                                                if (n.compareTo(orig as BigDecimal) != 0) changes[f.key] = n
                                            }
                                            PolicyKind.PCT -> {
                                                val pct = raw.replace(',', '.').toBigDecimalOrNull() ?: error("« ${f.label} » : nombre requis.")
                                                if (pct.signum() < 0) error("« ${f.label} » : nombre positif requis.")
                                                if (f.max != null && pct > BigDecimal.valueOf(f.max)) error("« ${f.label} » : ${f.max} au maximum.")
                                                val frac = pct.divide(BigDecimal(100), 6, RoundingMode.HALF_UP)
                                                if (frac.compareTo(orig as BigDecimal) != 0) changes[f.key] = frac
                                            }
                                        }
                                    }
                                }
                            } catch (e: IllegalStateException) {
                                error = e.message
                                return@Button
                            }
                            if (changes.isEmpty()) {
                                error = "Aucun changement à enregistrer."
                                return@Button
                            }
                            saving = true
                            scope.launch {
                                when (val res = container.backOffice.updateCreditPolicy(changes, r)) {
                                    is ApiResult.Ok -> {
                                        reason = ""
                                        load("Politique mise à jour.")
                                    }
                                    is ApiResult.Failure -> error = res.message
                                }
                                saving = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("policy_save_button"),
                    ) {
                        Text(if (saving) "Enregistrement…" else "Enregistrer")
                    }
                }
            }
        }
    }
}
