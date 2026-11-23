package cd.ctm.agent.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.data.ImageCompressor
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.BadgeTone
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmBadge
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmConfirmDialog
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.CtmStatusBadge
import cd.ctm.agent.ui.components.ImeiScannerDialog
import cd.ctm.agent.ui.components.usd
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.http.ApprovedDeliveryApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Composable
fun DeliveryScreen(
    container: AppContainer,
    vm: DeliveryViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    val mdmPolling by vm.mdmPolling.collectAsState()
    val mdmTimedOut by vm.mdmPollTimedOut.collectAsState()

    var approvedApps by remember { mutableStateOf<List<ApprovedDeliveryApplication>>(emptyList()) }
    var loadingApps by remember { mutableStateOf(true) }
    var appsError by remember { mutableStateOf<String?>(null) }
    var applicationId by rememberSaveable { mutableStateOf("") }
    var imeiInput by rememberSaveable { mutableStateOf("") }
    var photoObjectPath by rememberSaveable { mutableStateOf("") }
    var photoMessage by remember { mutableStateOf<String?>(null) }
    var processingPhoto by remember { mutableStateOf(false) }
    var showImeiScanner by rememberSaveable { mutableStateOf(false) }
    var pendingCameraAction by rememberSaveable { mutableStateOf("") } // "imei_scan" or "handover_photo"
    var confirmAbort by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val signaturePoints = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }

    val effectiveState = if (state is DeliveryViewModel.State.Error) {
        (state as DeliveryViewModel.State.Error).previousState
    } else {
        state
    }

    val currentDeliveryId = when (effectiveState) {
        is DeliveryViewModel.State.Started -> effectiveState.deliveryId
        is DeliveryViewModel.State.ImeiVerified -> effectiveState.deliveryId
        is DeliveryViewModel.State.ProofRecorded -> effectiveState.deliveryId
        is DeliveryViewModel.State.EnrollmentRequested -> effectiveState.deliveryId
        is DeliveryViewModel.State.Enrolled -> effectiveState.deliveryId
        is DeliveryViewModel.State.Completed -> effectiveState.deliveryId
        else -> ""
    }

    fun handlePhotoUri(uri: Uri) {
        val did = currentDeliveryId.trim()
        if (did.isBlank()) return
        processingPhoto = true
        photoMessage = null
        uiScope.launch {
            val newRelPath = "$did/${UUID.randomUUID()}_handover.jpg"
            val oldRelPath = photoObjectPath
            val ok = withContext(Dispatchers.IO) {
                val destFile = container.photoVault.create("handover/$newRelPath")
                val saved = ImageCompressor.compressUriToFile(context, uri, destFile)
                if (saved && oldRelPath.isNotBlank() && oldRelPath != newRelPath) {
                    container.photoVault.delete("handover/$oldRelPath")
                } else if (!saved) {
                    container.photoVault.delete("handover/$newRelPath")
                }
                deliveryCameraTempFile(context).delete()
                saved
            }
            processingPhoto = false
            if (ok) {
                photoObjectPath = newRelPath
            } else {
                photoMessage = context.getString(R.string.kyc_photo_compress_error)
            }
        }
    }

    val takePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            handlePhotoUri(deliveryCameraTempUri(context))
        } else {
            deliveryCameraTempFile(context).delete()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handlePhotoUri(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            when (pendingCameraAction) {
                "imei_scan" -> showImeiScanner = true
                "handover_photo" -> takePhotoLauncher.launch(deliveryCameraTempUri(context))
            }
        } else {
            photoMessage = context.getString(R.string.kyc_camera_denied)
        }
        pendingCameraAction = ""
    }

    fun requestCameraFor(action: String) {
        photoMessage = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (action == "imei_scan") showImeiScanner = true
            else takePhotoLauncher.launch(deliveryCameraTempUri(context))
        } else {
            pendingCameraAction = action
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    // Démarre le polling automatique MDM dès que l'enrôlement est demandé (ou repris après redémarrage).
    LaunchedEffect(effectiveState) {
        if (effectiveState is DeliveryViewModel.State.EnrollmentRequested) {
            vm.startEnrollmentPolling(effectiveState.deliveryId)
        }
        if (effectiveState is DeliveryViewModel.State.ProofRecorded && photoObjectPath.isNotBlank()) {
            withContext(Dispatchers.IO) {
                container.photoVault.delete("handover/$photoObjectPath")
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { vm.stopPolling() }
    }

    if (showImeiScanner) {
        ImeiScannerDialog(
            onDetected = { code ->
                imeiInput = code
                showImeiScanner = false
            },
            onDismiss = { showImeiScanner = false },
        )
    }

    if (confirmAbort) {
        CtmConfirmDialog(
            title = stringResource(R.string.delivery_abort_title),
            message = stringResource(R.string.delivery_abort_message),
            danger = true,
            onDismiss = { confirmAbort = false },
            onConfirm = {
                confirmAbort = false
                vm.reset()
                applicationId = ""
                imeiInput = ""
                photoObjectPath = ""
                signaturePoints.clear()
            },
        )
    }

    CtmScreenContainer(
        title = stringResource(R.string.delivery_title),
        subtitle = stringResource(R.string.delivery_subtitle),
        onBack = { vm.stopPolling(); onBack() },
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
                    if (photoMessage != null) {
                        CtmAlertBanner(AlertKind.ERROR, photoMessage!!)
                    }
                    CtmField(
                        label = stringResource(R.string.delivery_imei_label),
                        value = imeiInput,
                        onChange = { imeiInput = it },
                        keyboard = KeyboardType.Number,
                        testTag = "delivery_imei_input",
                    )
                    OutlinedButton(
                        onClick = { requestCameraFor("imei_scan") },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_scan_imei_button"),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.delivery_scan_imei))
                    }
                    Button(
                        onClick = { vm.verifyImei(effectiveState.deliveryId, imeiInput.trim()) },
                        modifier = Modifier.fillMaxWidth().testTag("delivery_verify_imei_button"),
                    ) {
                        Text(stringResource(R.string.delivery_verify_imei))
                    }
                }
            }

            is DeliveryViewModel.State.ImeiVerified -> {
                val photoFile = remember(photoObjectPath) {
                    if (photoObjectPath.isBlank()) null else container.photoVault.fileFor("handover/$photoObjectPath")
                }
                val photoPreview by produceState<ImageBitmap?>(initialValue = null, key1 = photoFile?.absolutePath) {
                    value = if (photoFile == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) {
                            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeFile(photoFile.absolutePath, opts)?.asImageBitmap()
                        }
                    }
                }

                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.delivery_step3_title), style = MaterialTheme.typography.titleMedium)
                        CtmBadge(
                            label = stringResource(if (photoFile != null) R.string.kyc_photo_ready else R.string.kyc_photo_missing),
                            tone = if (photoFile != null) BadgeTone.OK else BadgeTone.WARN,
                        )
                    }
                    CtmKeyValueRow("IMEI vérifié", effectiveState.imei.ifBlank { imeiInput }, mono = true)

                    if (photoMessage != null) {
                        CtmAlertBanner(AlertKind.ERROR, photoMessage!!)
                    }

                    if (photoPreview != null) {
                        Image(
                            bitmap = photoPreview!!,
                            contentDescription = stringResource(R.string.delivery_step3_title),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { requestCameraFor("handover_photo") },
                            enabled = !processingPhoto,
                            modifier = Modifier.weight(1f).testTag("delivery_take_photo_button"),
                        ) {
                            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.delivery_take_photo), maxLines = 1)
                        }
                        OutlinedButton(
                            onClick = {
                                photoMessage = null
                                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = !processingPhoto,
                            modifier = Modifier.weight(1f).testTag("delivery_pick_photo_button"),
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(
                                    if (photoObjectPath.isBlank()) R.string.delivery_choose_photo else R.string.delivery_replace_photo,
                                ),
                                maxLines = 1,
                            )
                        }
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
                                .onSizeChanged { canvasSize = it }
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
                            uiScope.launch {
                                val photoBytes = withContext(Dispatchers.IO) {
                                    container.photoVault.readBytes("handover/$photoObjectPath")
                                }
                                if (photoBytes == null || photoBytes.isEmpty()) {
                                    photoMessage = context.getString(R.string.kyc_photo_compress_error)
                                    return@launch
                                }
                                val pngBytes = withContext(Dispatchers.Default) {
                                    renderSignaturePng(
                                        strokes = signaturePoints.toList(),
                                        widthPx = canvasSize.width.toFloat(),
                                        heightPx = canvasSize.height.toFloat(),
                                    )
                                }
                                val hash = sha256Hex(pngBytes)
                                val sigObjectPath = "${effectiveState.deliveryId}/${UUID.randomUUID()}_signature.png"
                                vm.recordProofWithUpload(
                                    deliveryId = effectiveState.deliveryId,
                                    photoObjectPath = photoObjectPath,
                                    photoBytes = photoBytes,
                                    signatureObjectPath = sigObjectPath,
                                    signaturePngBytes = pngBytes,
                                    signatureHash = hash,
                                )
                            }
                        },
                        enabled = !processingPhoto && photoFile != null && signaturePoints.isNotEmpty() && state !is DeliveryViewModel.State.Working,
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
                    CtmAlertBanner(AlertKind.INFO, stringResource(R.string.delivery_step5_desc))
                    if (mdmPolling) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.delivery_polling_active),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (mdmTimedOut) {
                        CtmAlertBanner(AlertKind.WARN, stringResource(R.string.delivery_polling_timeout))
                    }
                    Button(
                        onClick = {
                            vm.checkEnrollment(effectiveState.deliveryId)
                            vm.startEnrollmentPolling(effectiveState.deliveryId)
                        },
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
                        photoObjectPath = ""
                        signaturePoints.clear()
                    },
                    modifier = Modifier.fillMaxWidth().testTag("delivery_new_button"),
                ) {
                    Text(stringResource(R.string.delivery_new))
                }
            }

            is DeliveryViewModel.State.Error -> Unit
        }

        if (effectiveState !is DeliveryViewModel.State.Idle &&
            effectiveState !is DeliveryViewModel.State.Working &&
            effectiveState !is DeliveryViewModel.State.Completed
        ) {
            TextButton(
                onClick = { confirmAbort = true },
                modifier = Modifier.fillMaxWidth().testTag("delivery_abort_button"),
            ) {
                Text(stringResource(R.string.delivery_abort_button), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun renderSignaturePng(strokes: List<List<Offset>>, widthPx: Float, heightPx: Float): ByteArray {
    val w = 800
    val h = 360
    val sx = if (widthPx > 1f) w / widthPx else 1f
    val sy = if (heightPx > 1f) h / heightPx else 1f
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(17, 24, 39)
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { pts ->
        if (pts.size > 1) {
            val path = android.graphics.Path()
            path.moveTo(pts.first().x * sx, pts.first().y * sy)
            for (i in 1 until pts.size) {
                path.lineTo(pts[i].x * sx, pts[i].y * sy)
            }
            canvas.drawPath(path, paint)
        }
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
    bmp.recycle()
    return out.toByteArray()
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun deliveryCameraTempFile(context: Context): File =
    File(context.filesDir, "camera_tmp/delivery_capture.jpg").apply { parentFile?.mkdirs() }

private fun deliveryCameraTempUri(context: Context): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", deliveryCameraTempFile(context))
