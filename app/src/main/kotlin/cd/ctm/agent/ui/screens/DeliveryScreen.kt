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
import androidx.compose.runtime.saveable.rememberSaveable
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
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.http.ApprovedDeliveryApplication
import java.security.MessageDigest

@Composable
fun DeliveryScreen(
    container: AppContainer,
    vm: DeliveryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    var approvedApps by remember { mutableStateOf<List<ApprovedDeliveryApplication>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var appsError by remember { mutableStateOf<String?>(null) }
    var applicationId by rememberSaveable { mutableStateOf("") }
    var imeiInput by rememberSaveable { mutableStateOf("") }
    var photoPath by rememberSaveable { mutableStateOf("") }
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
        when (val res = container.agentHttpApi.fetchApprovedApplicationsForDelivery()) {
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
        title = stringResource(R.string.delivery_title),
        subtitle = stringResource(R.string.delivery_subtitle),
        onBack = { vm.reset(); onBack() },
    ) {
        if (state is DeliveryViewModel.State.Error) {
            CtmAlertBanner(AlertKind.ERROR, (state as DeliveryViewModel.State.Error).message)
        }
        if (state is DeliveryViewModel.State.Working) {
            CtmLoadingView(stringResource(R.string.delivery_working))
        }

        when (effectiveState) {
            is DeliveryViewModel.State.Idle, is DeliveryViewModel.State.Working -> {
                CtmCard {
                    Text(stringResource(R.string.delivery_step1_title), style = MaterialTheme.typography.titleMedium)
                    CtmField(
                        label = stringResource(R.string.delivery_app_id_label),
                        value = applicationId,
                        onChange = { applicationId = it },
                        testTag = "delivery_application_id_input",
                    )
                    Button(
                        onClick = { vm.startDelivery(applicationId.trim()) },
                        enabled = applicationId.isNotBlank() && state !is DeliveryViewModel.State.Working,
                        modifier = Modifier.fillMaxWidth().testTag("delivery_start_button"),
                    ) {
                        Text(stringResource(R.string.delivery_start))
                    }
                }

                if (loadingApps) {
                    CtmLoadingView(stringResource(R.string.delivery_loading_apps))
                } else if (appsError != null) {
                    CtmAlertBanner(AlertKind.INFO, appsError!!)
                } else if (approvedApps.isNotEmpty()) {
                    Text(stringResource(R.string.delivery_ready_list), style = MaterialTheme.typography.titleSmall)
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
                                Text(stringResource(R.string.delivery_deliver_this))
                            }
                        }
                    }
                }
            }

            is DeliveryViewModel.State.Started -> {
                CtmCard {
                    Text(stringResource(R.string.delivery_step2_title), style = MaterialTheme.typography.titleMedium)
                    CtmKeyValueRow("ID livraison", effectiveState.deliveryId, mono = true)
                    CtmField(
                        label = stringResource(R.string.delivery_imei_label),
                        value = imeiInput,
                        onChange = { imeiInput = it },
                        keyboard = KeyboardType.Number,
                        testTag = "delivery_imei_input",
                    )
                    Button(
                        onClick = { vm.verifyImei(effectiveState.deliveryId, imeiInput.trim()) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_verify_imei_button"),
                    ) {
                        Text(stringResource(R.string.delivery_verify_imei))
                    }
                }
            }

            is DeliveryViewModel.State.ImeiVerified -> {
                CtmCard {
                    Text(stringResource(R.string.delivery_step3_title), style = MaterialTheme.typography.titleMedium)
                    CtmKeyValueRow("IMEI vérifié", effectiveState.imei.ifBlank { imeiInput }, mono = true)

                    OutlinedButton(
                        onClick = {
                            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_pick_photo_button"),
                    ) {
                        Text(
                            stringResource(
                                if (photoPath.isBlank()) R.string.delivery_choose_photo else R.string.delivery_replace_photo,
                            ),
                        )
                    }
                    if (photoPath.isNotBlank()) {
                        CtmKeyValueRow("Fichier prêt", photoPath, mono = true)
                    }

                    Text(
                        text = stringResource(R.string.delivery_signature_hint),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    val strokeColor = MaterialTheme.colorScheme.primary
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { start -> currentStroke = listOf(start) },
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
                                if (pts.size > 1) {
                                    val path = Path().apply {
                                        moveTo(pts.first().x, pts.first().y)
                                        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(
                            onClick = {
                                signaturePoints.clear()
                                currentStroke = emptyList()
                            },
                        ) { Text(stringResource(R.string.delivery_signature_clear)) }
                        Spacer(Modifier.weight(1f))
                    }

                    Button(
                        onClick = {
                            val sigPayload = signaturePoints.flatten().joinToString(";") { "${it.x.toInt()},${it.y.toInt()}" }
                            val hash = if (sigPayload.isBlank()) "" else sha256Hex("${effectiveState.deliveryId}:$sigPayload")
                            vm.recordProof(effectiveState.deliveryId, photoPath, hash)
                        },
                        enabled = photoPath.isNotBlank() && signaturePoints.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().testTag("delivery_send_proof_button"),
                    ) {
                        Text(stringResource(R.string.delivery_send_proof))
                    }
                }
            }

            is DeliveryViewModel.State.ProofRecorded -> {
                CtmCard {
                    Text(stringResource(R.string.delivery_step4_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.delivery_step4_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { vm.requestEnrollment(effectiveState.deliveryId) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_request_mdm_button"),
                    ) {
                        Text(stringResource(R.string.delivery_request_mdm))
                    }
                }
            }

            is DeliveryViewModel.State.EnrollmentRequested -> {
                CtmCard {
                    Text(stringResource(R.string.delivery_step5_title), style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(
                        AlertKind.INFO,
                        stringResource(R.string.delivery_step5_desc),
                    )
                    Button(
                        onClick = { vm.checkEnrollment(effectiveState.deliveryId) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_check_mdm_button"),
                    ) {
                        Text(stringResource(R.string.delivery_check_mdm))
                    }
                }
            }

            is DeliveryViewModel.State.Enrolled -> {
                CtmCard {
                    Text(stringResource(R.string.delivery_step6_title), style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(AlertKind.OK, stringResource(R.string.delivery_step6_desc))
                    Button(
                        onClick = { vm.completeDelivery(effectiveState.deliveryId) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_complete_button"),
                    ) {
                        Text(stringResource(R.string.delivery_complete))
                    }
                }
            }

            is DeliveryViewModel.State.Completed -> {
                CtmAlertBanner(AlertKind.OK, stringResource(R.string.delivery_completed_msg))
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
                    Text(stringResource(R.string.delivery_new))
                }
            }

            is DeliveryViewModel.State.Error -> Unit
        }
    }
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
