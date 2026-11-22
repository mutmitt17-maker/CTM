package cd.ctm.agent.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.http.LoanApplicationSummary
import java.security.MessageDigest

@Composable
fun DeliveryScreen(
    container: AppContainer,
    vm: DeliveryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var approvedApps by remember { mutableStateOf<List<LoanApplicationSummary>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var appsError by remember { mutableStateOf<String?>(null) }
    var applicationId by remember { mutableStateOf("") }
    var imeiInput by remember { mutableStateOf("") }
    var photoPath by remember { mutableStateOf("") }
    val signaturePoints = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val currentDeliveryId = when (val s = state) {
        is DeliveryViewModel.State.Started -> s.deliveryId
        is DeliveryViewModel.State.ImeiVerified -> s.deliveryId
        is DeliveryViewModel.State.ProofRecorded -> s.deliveryId
        is DeliveryViewModel.State.EnrollmentRequested -> s.deliveryId
        is DeliveryViewModel.State.Enrolled -> s.deliveryId
        is DeliveryViewModel.State.Completed -> s.deliveryId
        is DeliveryViewModel.State.Error -> when (val prev = s.previousState) {
            is DeliveryViewModel.State.Started -> prev.deliveryId
            is DeliveryViewModel.State.ImeiVerified -> prev.deliveryId
            is DeliveryViewModel.State.ProofRecorded -> prev.deliveryId
            is DeliveryViewModel.State.EnrollmentRequested -> prev.deliveryId
            is DeliveryViewModel.State.Enrolled -> prev.deliveryId
            else -> ""
        }
        else -> ""
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null && currentDeliveryId.isNotBlank()) {
            val bucketPath = "$currentDeliveryId/proof.jpg"
            try {
                val target = container.photoVault.create(bucketPath)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                photoPath = bucketPath
            } catch (_: Exception) {
                photoPath = bucketPath
            }
        }
    }

    LaunchedEffect(Unit) {
        loadingApps = true
        when (val res = container.backOffice.fetchApprovedApplicationsForDelivery()) {
            is ApiResult.Ok -> {
                approvedApps = res.value
                appsError = null
            }
            is ApiResult.Failure -> appsError = res.message
        }
        loadingApps = false
    }

    val effectiveState = if (state is DeliveryViewModel.State.Error) {
        (state as DeliveryViewModel.State.Error).previousState
    } else {
        state
    }

    CtmScreenContainer(
        title = "Livraison téléphone",
        subtitle = "Procédure en ligne (IMEI, preuve de remise, MDM)",
        onBack = { vm.reset(); onBack() },
    ) {
        if (state is DeliveryViewModel.State.Error) {
            CtmAlertBanner(AlertKind.ERROR, (state as DeliveryViewModel.State.Error).message)
        }
        if (state is DeliveryViewModel.State.Working) {
            CtmLoadingView("Communication avec le serveur…")
        }

        when (effectiveState) {
            is DeliveryViewModel.State.Idle, is DeliveryViewModel.State.Working -> {
                CtmCard {
                    Text("Étape 1 · Sélectionner le dossier approuvé", style = MaterialTheme.typography.titleMedium)
                    CtmField(
                        label = "ID du dossier (UUID)",
                        value = applicationId,
                        onChange = { applicationId = it },
                        testTag = "delivery_application_id_input",
                    )
                    Button(
                        onClick = { vm.startDelivery(applicationId.trim()) },
                        enabled = applicationId.isNotBlank() && state !is DeliveryViewModel.State.Working,
                        modifier = Modifier.fillMaxWidth().testTag("delivery_start_button"),
                    ) {
                        Text("Démarrer la livraison")
                    }
                }

                if (loadingApps) {
                    CtmLoadingView("Recherche des dossiers approuvés…")
                } else if (appsError != null) {
                    CtmAlertBanner(AlertKind.INFO, appsError!!)
                } else if (approvedApps.isNotEmpty()) {
                    Text("Dossiers approuvés prêts à livrer", style = MaterialTheme.typography.titleSmall)
                    approvedApps.forEach { app ->
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
                                )
                                CtmStatusBadge(app.status)
                            }
                            app.deviceLabel?.takeIf { it.isNotBlank() }?.let { label ->
                                CtmKeyValueRow("Appareil", label)
                            }
                            app.deviceImei?.takeIf { it.isNotBlank() }?.let { imei ->
                                CtmKeyValueRow("IMEI attendu", imei, mono = true)
                            }
                            CtmKeyValueRow("Montant financé", usd(app.financedAmount), mono = true)
                            OutlinedButton(
                                onClick = {
                                    applicationId = app.id
                                    vm.startDelivery(app.id)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Livrer ce dossier")
                            }
                        }
                    }
                }
            }

            is DeliveryViewModel.State.Started -> {
                CtmCard {
                    Text("Étape 2 · Vérifier l'IMEI du téléphone", style = MaterialTheme.typography.titleMedium)
                    CtmKeyValueRow("ID livraison", effectiveState.deliveryId, mono = true)
                    CtmField(
                        label = "IMEI scanné ou saisi (15 chiffres)",
                        value = imeiInput,
                        onChange = { imeiInput = it },
                        keyboard = KeyboardType.Number,
                        testTag = "delivery_imei_input",
                    )
                    Button(
                        onClick = { vm.verifyImei(effectiveState.deliveryId, imeiInput.trim()) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_verify_imei_button"),
                    ) {
                        Text("Vérifier l'IMEI")
                    }
                }
            }

            is DeliveryViewModel.State.ImeiVerified -> {
                CtmCard {
                    Text("Étape 3 · Preuve de remise (photo et signature)", style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(AlertKind.OK, "IMEI vérifié : ${effectiveState.imei.ifBlank { imeiInput }}")
                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_pick_photo_button"),
                    ) {
                        Text(if (photoPath.isBlank()) "Choisir la photo de remise" else "Photo sélectionnée : $photoPath")
                    }
                    CtmField(
                        label = "Chemin photo dans le bucket handover",
                        value = photoPath.ifBlank { "${effectiveState.deliveryId}/proof.jpg" }.also {
                            if (photoPath.isBlank()) photoPath = it
                        },
                        onChange = { photoPath = it },
                        testTag = "delivery_photo_path_input",
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Signature du client", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { signaturePoints.clear(); currentStroke = emptyList() }) {
                            Text("Effacer")
                        }
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (signaturePoints.isEmpty() && currentStroke.isEmpty()) {
                                Text(
                                    text = "Signez ici avec le doigt",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.align(Alignment.Center),
                                )
                            }
                            val strokeColor = MaterialTheme.colorScheme.primary
                            Canvas(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .pointerInput(Unit) {
                                        detectDragGestures(
                                            onDragStart = { offset -> currentStroke = listOf(offset) },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                currentStroke = currentStroke + change.position
                                            },
                                            onDragEnd = {
                                                if (currentStroke.isNotEmpty()) {
                                                    signaturePoints.add(currentStroke)
                                                    currentStroke = emptyList()
                                                }
                                            },
                                        )
                                    },
                            ) {
                                val allStrokes = signaturePoints + listOf(currentStroke)
                                allStrokes.forEach { pts ->
                                    if (pts.size >= 2) {
                                        val path = Path().apply {
                                            moveTo(pts.first().x, pts.first().y)
                                            pts.drop(1).forEach { lineTo(it.x, it.y) }
                                        }
                                        drawPath(
                                            path = path,
                                            color = strokeColor,
                                            style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val sigRaw = signaturePoints.flatten().joinToString(";") { "${it.x.toInt()},${it.y.toInt()}" }
                            val hash = if (sigRaw.isBlank()) "" else sha256Hex(sigRaw)
                            vm.recordProof(effectiveState.deliveryId, photoPath.trim(), hash)
                        },
                        enabled = signaturePoints.isNotEmpty() && photoPath.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().testTag("delivery_record_proof_button"),
                    ) {
                        Text("Enregistrer la preuve de remise")
                    }
                }
            }

            is DeliveryViewModel.State.ProofRecorded -> {
                CtmCard {
                    Text("Étape 4 · Enrôlement MDM", style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(AlertKind.OK, "Preuve de remise enregistrée.")
                    Text(
                        text = "Demandez au serveur de préparer l'enrôlement de verrouillage à distance (Knox Guard / ABM / Android Enterprise).",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { vm.requestEnrollment(effectiveState.deliveryId) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_request_mdm_button"),
                    ) {
                        Text("Demander l'enrôlement MDM")
                    }
                }
            }

            is DeliveryViewModel.State.EnrollmentRequested -> {
                CtmCard {
                    Text("Étape 5 · Confirmation MDM", style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(
                        AlertKind.INFO,
                        "Enrôlement demandé. Vérifiez le statut jusqu'à ce que le serveur confirme l'enrôlement.",
                    )
                    Button(
                        onClick = { vm.checkEnrollment(effectiveState.deliveryId) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_check_mdm_button"),
                    ) {
                        Text("Vérifier le statut MDM")
                    }
                }
            }

            is DeliveryViewModel.State.Enrolled -> {
                CtmCard {
                    Text("Étape 6 · Clôturer la livraison", style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(AlertKind.OK, "Appareil enrôlé dans le MDM. La remise peut être clôturée.")
                    Button(
                        onClick = { vm.completeDelivery(effectiveState.deliveryId) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_complete_button"),
                    ) {
                        Text("Terminer la livraison")
                    }
                }
            }

            is DeliveryViewModel.State.Completed -> {
                CtmAlertBanner(AlertKind.OK, "Livraison terminée et contrat activé !")
                Button(
                    onClick = {
                        vm.reset()
                        applicationId = ""
                        imeiInput = ""
                        photoPath = ""
                        signaturePoints.clear()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("delivery_new_button"),
                ) {
                    Text("Nouvelle livraison")
                }
            }

            is DeliveryViewModel.State.Error -> Unit
        }
    }
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
