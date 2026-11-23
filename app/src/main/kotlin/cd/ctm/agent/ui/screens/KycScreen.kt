package cd.ctm.agent.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ai.AiOutcome
import cd.ctm.agent.ai.KycIdAnalysisResult
import cd.ctm.agent.ai.KycSelfieAnalysisResult
import cd.ctm.agent.data.ImageCompressor
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.BadgeTone
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmBadge
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmChoiceRow
import cd.ctm.agent.ui.components.CtmField
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.kyc.KycViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

private data class CustomerOption(
    val id: String,
    val fullName: String,
    val phoneNumber: String?,
    val isLocal: Boolean,
)

@Composable
fun KycScreen(
    container: AppContainer,
    vm: KycViewModel,
    initialCustomerId: String?,
    canMakeApplication: Boolean,
    onApplication: (String) -> Unit,
    onSyncNow: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val state by vm.state.collectAsState()
    val syncMessage by container.syncMessage.collectAsState()
    val syncRunning by container.syncRunning.collectAsState()

    var customerId by rememberSaveable(initialCustomerId) { mutableStateOf(initialCustomerId.orEmpty()) }
    var birthDate by rememberSaveable { mutableStateOf("") }
    var commune by rememberSaveable { mutableStateOf("") }
    var neighborhood by rememberSaveable { mutableStateOf("") }
    var occupation by rememberSaveable { mutableStateOf("") }
    var incomeSource by rememberSaveable { mutableStateOf("") }
    var declaredMonthlyIncomeUsd by rememberSaveable { mutableStateOf("") }
    var idDocumentTypeWire by rememberSaveable { mutableStateOf(KycViewModel.IdDocumentType.NATIONAL_ID.wire) }
    var idPhotoPath by rememberSaveable { mutableStateOf("") }
    var selfiePath by rememberSaveable { mutableStateOf("") }

    var emergencyFullName by rememberSaveable { mutableStateOf("") }
    var emergencyPhone by rememberSaveable { mutableStateOf("+243") }
    var emergencyRelationshipWire by rememberSaveable { mutableStateOf(KycViewModel.Relationship.PARENT.wire) }
    var emergencyIsGuarantor by rememberSaveable { mutableStateOf(false) }

    var consentBiometrics by rememberSaveable { mutableStateOf(false) }
    var consentCreditTerms by rememberSaveable { mutableStateOf(false) }
    var consentDeviceLock by rememberSaveable { mutableStateOf(false) }

    var pendingCameraTarget by rememberSaveable { mutableStateOf("") }
    var photoStatusMessage by remember { mutableStateOf<String?>(null) }
    var processingPhoto by remember { mutableStateOf(false) }

    var analyzingIdAi by remember { mutableStateOf(false) }
    var idAiResult by remember { mutableStateOf<KycIdAnalysisResult?>(null) }
    var idAiError by remember { mutableStateOf<String?>(null) }
    var idAiApplied by remember { mutableStateOf(false) }

    var analyzingSelfieAi by remember { mutableStateOf(false) }
    var selfieAiResult by remember { mutableStateOf<KycSelfieAnalysisResult?>(null) }
    var selfieAiError by remember { mutableStateOf<String?>(null) }

    var customerOptions by remember { mutableStateOf<List<CustomerOption>>(emptyList()) }
    var loadingCustomers by remember { mutableStateOf(false) }

    LaunchedEffect(initialCustomerId) {
        if (initialCustomerId.isNullOrBlank()) {
            loadingCustomers = true
            val locals = withContext(Dispatchers.IO) {
                container.syncStore.localCustomers(limit = 30).map {
                    CustomerOption(id = it.id, fullName = it.fullName, phoneNumber = it.phoneNumber, isLocal = true)
                }
            }
            val remote = when (val res = container.agentHttpApi.fetchRecentCustomers(limit = 30)) {
                is ApiResult.Ok -> res.value.map {
                    CustomerOption(id = it.id, fullName = it.fullName, phoneNumber = it.phoneNumber, isLocal = false)
                }
                is ApiResult.Failure -> emptyList()
            }
            val merged = LinkedHashMap<String, CustomerOption>()
            locals.forEach { merged[it.id] = it }
            remote.forEach { if (!merged.containsKey(it.id)) merged[it.id] = it }
            customerOptions = merged.values.toList()
            loadingCustomers = false
        }
    }

    LaunchedEffect(state) {
        if (state is KycViewModel.State.Queued) {
            container.refreshQueueState()
        }
    }

    fun runIdPhotoAi(relPath: String) {
        if (relPath.isBlank()) return
        analyzingIdAi = true
        idAiResult = null
        idAiError = null
        idAiApplied = false
        uiScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val file = container.photoVault.fileFor("kyc/$relPath") ?: return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
            if (bmp == null) {
                analyzingIdAi = false
                idAiError = context.getString(R.string.kyc_photo_compress_error)
                return@launch
            }
            val res = try {
                container.aiAssistant.analyzeKycIdPhoto(bmp, idDocumentTypeWire)
            } finally {
                bmp.recycle()
            }
            analyzingIdAi = false
            when (res) {
                is AiOutcome.Success -> idAiResult = res.data
                is AiOutcome.Error -> idAiError = res.message
            }
        }
    }

    fun runSelfieAi(relPath: String) {
        if (relPath.isBlank()) return
        analyzingSelfieAi = true
        selfieAiResult = null
        selfieAiError = null
        uiScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val file = container.photoVault.fileFor("kyc/$relPath") ?: return@withContext null
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            }
            if (bmp == null) {
                analyzingSelfieAi = false
                selfieAiError = context.getString(R.string.kyc_photo_compress_error)
                return@launch
            }
            val res = try {
                container.aiAssistant.analyzeKycSelfieQuality(bmp)
            } finally {
                bmp.recycle()
            }
            analyzingSelfieAi = false
            when (res) {
                is AiOutcome.Success -> selfieAiResult = res.data
                is AiOutcome.Error -> selfieAiError = res.message
            }
        }
    }

    fun handleCapturedUri(uri: Uri) {
        val target = pendingCameraTarget
        val cid = customerId.trim()
        if (target.isBlank() || cid.isBlank()) return
        processingPhoto = true
        photoStatusMessage = null
        uiScope.launch {
            val newRelPath = "$cid/${UUID.randomUUID()}_$target.jpg"
            val oldRelPath = if (target == "id") idPhotoPath else selfiePath
            val ok = withContext(Dispatchers.IO) {
                val destFile = container.photoVault.create("kyc/$newRelPath")
                val saved = ImageCompressor.compressUriToFile(context, uri, destFile)
                if (saved && oldRelPath.isNotBlank() && oldRelPath != newRelPath) {
                    container.photoVault.delete("kyc/$oldRelPath")
                } else if (!saved) {
                    container.photoVault.delete("kyc/$newRelPath")
                }
                cameraTempFile(context).delete()
                saved
            }
            processingPhoto = false
            if (ok) {
                if (target == "id") {
                    idPhotoPath = newRelPath
                    runIdPhotoAi(newRelPath)
                } else {
                    selfiePath = newRelPath
                    runSelfieAi(newRelPath)
                }
                container.refreshQueueState()
            } else {
                photoStatusMessage = context.getString(R.string.kyc_photo_compress_error)
            }
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val tmpUri = cameraTempUri(context)
            handleCapturedUri(tmpUri)
        } else {
            cameraTempFile(context).delete()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            takePictureLauncher.launch(cameraTempUri(context))
        } else {
            photoStatusMessage = context.getString(R.string.kyc_camera_denied)
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            handleCapturedUri(uri)
        }
    }

    fun startCameraCapture(target: String) {
        if (customerId.trim().isBlank()) {
            photoStatusMessage = context.getString(R.string.kyc_select_customer_first)
            return
        }
        photoStatusMessage = null
        pendingCameraTarget = target
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            takePictureLauncher.launch(cameraTempUri(context))
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun startGalleryPick(target: String) {
        if (customerId.trim().isBlank()) {
            photoStatusMessage = context.getString(R.string.kyc_select_customer_first)
            return
        }
        photoStatusMessage = null
        pendingCameraTarget = target
        galleryPickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    CtmScreenContainer(
        title = stringResource(R.string.kyc_title),
        subtitle = stringResource(R.string.kyc_subtitle),
        onBack = { vm.reset(); onBack() },
    ) {
        when (val s = state) {
            is KycViewModel.State.Queued -> {
                CtmAlertBanner(AlertKind.OK, stringResource(R.string.kyc_queued_msg))
                if (syncMessage.isNotBlank()) {
                    CtmAlertBanner(AlertKind.INFO, syncMessage)
                }
                if (canMakeApplication) {
                    Button(
                        onClick = { vm.reset(); onApplication(s.customerId) },
                        modifier = Modifier.fillMaxWidth().testTag("kyc_create_application_button"),
                    ) {
                        Text(stringResource(R.string.customer_create_application))
                    }
                }
                OutlinedButton(
                    onClick = { onSyncNow() },
                    enabled = !syncRunning,
                    modifier = Modifier.fillMaxWidth().testTag("kyc_sync_now_button"),
                ) {
                    Text(stringResource(if (syncRunning) R.string.sync_running else R.string.kyc_sync_now))
                }
                OutlinedButton(
                    onClick = { vm.reset(); onBack() },
                    modifier = Modifier.fillMaxWidth().testTag("kyc_done_button"),
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }

            else -> {
                CtmCard {
                    Text(stringResource(R.string.kyc_step_customer), style = MaterialTheme.typography.titleMedium)
                    if (initialCustomerId.isNullOrBlank()) {
                        if (loadingCustomers) {
                            CtmLoadingView(stringResource(R.string.kyc_loading_customers))
                        } else if (customerOptions.isEmpty()) {
                            Text(
                                stringResource(R.string.kyc_no_customers),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            customerOptions.take(10).forEach { cust ->
                                val label = buildString {
                                    append(cust.fullName)
                                    if (!cust.phoneNumber.isNullOrBlank()) {
                                        append(" · ")
                                        append(cust.phoneNumber)
                                    }
                                    append(
                                        if (cust.isLocal) " [${stringResource(R.string.kyc_badge_local)}]"
                                        else " [${stringResource(R.string.kyc_badge_server)}]",
                                    )
                                }
                                CtmChoiceRow(
                                    selected = customerId == cust.id,
                                    label = label,
                                    onClick = { customerId = cust.id },
                                )
                            }
                        }
                    }
                    CtmField(
                        label = stringResource(R.string.kyc_customer_id_label),
                        value = customerId,
                        onChange = { customerId = it },
                        enabled = initialCustomerId.isNullOrBlank(),
                        testTag = "kyc_customer_id",
                    )
                }

                CtmCard {
                    Text(stringResource(R.string.kyc_step_identity), style = MaterialTheme.typography.titleMedium)
                    CtmField(
                        label = stringResource(R.string.kyc_birth_date),
                        value = birthDate,
                        onChange = { birthDate = it },
                        placeholder = "1995-04-12",
                        testTag = "kyc_birth_date",
                    )
                    CtmField(
                        label = stringResource(R.string.kyc_commune),
                        value = commune,
                        onChange = { commune = it },
                        testTag = "kyc_commune",
                    )
                    CtmField(
                        label = stringResource(R.string.kyc_neighborhood),
                        value = neighborhood,
                        onChange = { neighborhood = it },
                        testTag = "kyc_neighborhood",
                    )
                    CtmField(
                        label = stringResource(R.string.kyc_occupation),
                        value = occupation,
                        onChange = { occupation = it },
                        testTag = "kyc_occupation",
                    )
                    CtmField(
                        label = stringResource(R.string.kyc_income_source),
                        value = incomeSource,
                        onChange = { incomeSource = it },
                        testTag = "kyc_income_source",
                    )
                    CtmField(
                        label = stringResource(R.string.kyc_monthly_income),
                        value = declaredMonthlyIncomeUsd,
                        onChange = { declaredMonthlyIncomeUsd = it },
                        keyboard = KeyboardType.Decimal,
                        testTag = "kyc_monthly_income",
                    )
                    Text(
                        text = stringResource(R.string.kyc_doc_type_label),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    KycViewModel.IdDocumentType.entries.forEach { docType ->
                        CtmChoiceRow(
                            selected = idDocumentTypeWire == docType.wire,
                            label = docType.label,
                            onClick = { idDocumentTypeWire = docType.wire },
                        )
                    }
                }

                CtmCard {
                    Text(stringResource(R.string.kyc_step_photos), style = MaterialTheme.typography.titleMedium)
                    CtmAlertBanner(AlertKind.INFO, stringResource(R.string.kyc_biometrics_server_notice))
                    if (photoStatusMessage != null) {
                        CtmAlertBanner(AlertKind.ERROR, photoStatusMessage!!)
                    }
                    KycPhotoCaptureBlock(
                        title = stringResource(R.string.kyc_doc_photo_title),
                        relPath = idPhotoPath,
                        photoVault = container.photoVault,
                        busy = processingPhoto,
                        analyzingAi = analyzingIdAi,
                        qualityBadge = idAiResult?.let {
                            val label = if (it.qualityOk) {
                                stringResource(R.string.kyc_ai_quality_ok, it.qualityScore)
                            } else {
                                stringResource(R.string.kyc_ai_quality_warn, it.qualityScore)
                            }
                            label to (if (it.qualityOk) BadgeTone.OK else BadgeTone.WARN)
                        },
                        aiFeedback = idAiResult?.qualityFeedback,
                        aiError = idAiError,
                        aiActionLabel = if (idAiResult != null && !idAiApplied &&
                            (idAiResult!!.birthDate.isNotBlank() || idAiResult!!.commune.isNotBlank() ||
                                idAiResult!!.neighborhood.isNotBlank() || idAiResult!!.detectedDocType != "unknown")
                        ) {
                            stringResource(R.string.kyc_ai_apply_fields)
                        } else null,
                        aiAppliedNotice = if (idAiApplied) stringResource(R.string.kyc_ai_fields_applied) else null,
                        takeTag = "kyc_take_id_photo_button",
                        galleryTag = "kyc_pick_id_photo_button",
                        analyzeTag = "kyc_analyze_id_ai_button",
                        applyTag = "kyc_apply_ai_id_button",
                        onTakePhoto = { startCameraCapture("id") },
                        onPickGallery = { startGalleryPick("id") },
                        onAnalyzeAi = { runIdPhotoAi(idPhotoPath) },
                        onApplyAiFields = {
                            val r = idAiResult ?: return@KycPhotoCaptureBlock
                            if (Regex("""\d{4}-\d{2}-\d{2}""").matches(r.birthDate)) birthDate = r.birthDate
                            if (r.commune.isNotBlank()) commune = r.commune
                            if (r.neighborhood.isNotBlank()) neighborhood = r.neighborhood
                            if (r.detectedDocType in listOf("national_id", "voter_card", "passport", "driver_license")) {
                                idDocumentTypeWire = r.detectedDocType
                            }
                            idAiApplied = true
                        },
                    )
                    KycPhotoCaptureBlock(
                        title = stringResource(R.string.kyc_selfie_photo_title),
                        relPath = selfiePath,
                        photoVault = container.photoVault,
                        busy = processingPhoto,
                        analyzingAi = analyzingSelfieAi,
                        qualityBadge = selfieAiResult?.let {
                            val label = if (it.qualityOk) {
                                stringResource(R.string.kyc_ai_quality_ok, it.qualityScore)
                            } else {
                                stringResource(R.string.kyc_ai_quality_warn, it.qualityScore)
                            }
                            label to (if (it.qualityOk) BadgeTone.OK else BadgeTone.WARN)
                        },
                        aiFeedback = selfieAiResult?.qualityFeedback,
                        aiError = selfieAiError,
                        takeTag = "kyc_take_selfie_button",
                        galleryTag = "kyc_pick_selfie_button",
                        analyzeTag = "kyc_analyze_selfie_ai_button",
                        onTakePhoto = { startCameraCapture("selfie") },
                        onPickGallery = { startGalleryPick("selfie") },
                        onAnalyzeAi = { runSelfieAi(selfiePath) },
                    )
                }

                CtmCard {
                    Text(stringResource(R.string.kyc_step_contact), style = MaterialTheme.typography.titleMedium)
                    CtmField(
                        label = stringResource(R.string.kyc_contact_name),
                        value = emergencyFullName,
                        onChange = { emergencyFullName = it },
                        testTag = "kyc_contact_name",
                    )
                    CtmField(
                        label = stringResource(R.string.kyc_contact_phone),
                        value = emergencyPhone,
                        onChange = { emergencyPhone = it },
                        keyboard = KeyboardType.Phone,
                        testTag = "kyc_contact_phone",
                    )
                    Text(
                        text = stringResource(R.string.kyc_contact_relationship),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    KycViewModel.Relationship.entries.forEach { rel ->
                        CtmChoiceRow(
                            selected = emergencyRelationshipWire == rel.wire,
                            label = rel.label,
                            onClick = { emergencyRelationshipWire = rel.wire },
                        )
                    }
                    CheckRow(
                        checked = emergencyIsGuarantor,
                        label = stringResource(R.string.kyc_contact_guarantor),
                        onToggle = { emergencyIsGuarantor = !emergencyIsGuarantor },
                        tag = "kyc_contact_guarantor_checkbox",
                    )
                }

                CtmCard {
                    Text(stringResource(R.string.kyc_step_consents), style = MaterialTheme.typography.titleMedium)
                    CheckRow(
                        checked = consentBiometrics,
                        label = stringResource(R.string.kyc_consent_biometrics),
                        onToggle = { consentBiometrics = !consentBiometrics },
                        tag = "kyc_consent_biometrics_checkbox",
                    )
                    CheckRow(
                        checked = consentCreditTerms,
                        label = stringResource(R.string.kyc_consent_credit_terms),
                        onToggle = { consentCreditTerms = !consentCreditTerms },
                        tag = "kyc_consent_credit_checkbox",
                    )
                    CheckRow(
                        checked = consentDeviceLock,
                        label = stringResource(R.string.kyc_consent_device_lock),
                        onToggle = { consentDeviceLock = !consentDeviceLock },
                        tag = "kyc_consent_mdm_checkbox",
                    )

                    if (s is KycViewModel.State.Error) {
                        CtmAlertBanner(AlertKind.ERROR, s.message)
                    }

                    Button(
                        onClick = {
                            vm.submit(
                                KycViewModel.Form(
                                    customerId = customerId,
                                    birthDate = birthDate,
                                    commune = commune,
                                    neighborhood = neighborhood,
                                    occupation = occupation,
                                    incomeSource = incomeSource,
                                    declaredMonthlyIncomeUsd = declaredMonthlyIncomeUsd,
                                    idDocumentType = KycViewModel.IdDocumentType.fromWire(idDocumentTypeWire),
                                    idDocumentPhotoPath = idPhotoPath,
                                    selfiePath = selfiePath,
                                    emergencyFullName = emergencyFullName,
                                    emergencyPhone = emergencyPhone,
                                    emergencyRelationship = KycViewModel.Relationship.fromWire(emergencyRelationshipWire),
                                    emergencyIsGuarantor = emergencyIsGuarantor,
                                    consentBiometrics = consentBiometrics,
                                    consentCreditTerms = consentCreditTerms,
                                    consentDeviceLock = consentDeviceLock,
                                ),
                            )
                        },
                        enabled = s !is KycViewModel.State.Saving && !processingPhoto,
                        modifier = Modifier.fillMaxWidth().testTag("kyc_submit_button"),
                    ) {
                        Text(stringResource(R.string.kyc_submit))
                    }
                }
            }
        }
    }
}

@Composable
private fun KycPhotoCaptureBlock(
    title: String,
    relPath: String,
    photoVault: cd.ctm.agent.data.AndroidPhotoVault,
    busy: Boolean,
    analyzingAi: Boolean = false,
    qualityBadge: Pair<String, BadgeTone>? = null,
    aiFeedback: String? = null,
    aiError: String? = null,
    aiActionLabel: String? = null,
    aiAppliedNotice: String? = null,
    takeTag: String,
    galleryTag: String,
    analyzeTag: String,
    applyTag: String? = null,
    onTakePhoto: () -> Unit,
    onPickGallery: () -> Unit,
    onAnalyzeAi: () -> Unit,
    onApplyAiFields: () -> Unit = {},
) {
    val file = remember(relPath) {
        if (relPath.isBlank()) null else photoVault.fileFor("kyc/$relPath")
    }
    val preview by produceState<ImageBitmap?>(initialValue = null, key1 = file?.absolutePath) {
        value = if (file == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(file.absolutePath, opts)?.asImageBitmap()
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (qualityBadge != null) {
                    CtmBadge(qualityBadge.first, qualityBadge.second)
                } else if (file != null) {
                    CtmBadge(stringResource(R.string.kyc_photo_ready), BadgeTone.OK)
                } else {
                    CtmBadge(stringResource(R.string.kyc_photo_missing), BadgeTone.WARN)
                }
            }

            if (preview != null) {
                Image(
                    bitmap = preview!!,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                )
            }

            if (analyzingAi) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = stringResource(R.string.kyc_ai_analyzing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (!aiFeedback.isNullOrBlank()) {
                val kind = if (qualityBadge?.second == BadgeTone.OK) AlertKind.OK else AlertKind.WARN
                CtmAlertBanner(kind, aiFeedback)
            } else if (!aiError.isNullOrBlank()) {
                CtmAlertBanner(AlertKind.INFO, stringResource(R.string.kyc_ai_offline_note, aiError))
            }

            if (aiActionLabel != null) {
                Button(
                    onClick = onApplyAiFields,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (applyTag != null) Modifier.testTag(applyTag) else Modifier),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(aiActionLabel)
                }
            } else if (!aiAppliedNotice.isNullOrBlank()) {
                CtmAlertBanner(AlertKind.OK, aiAppliedNotice)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onTakePhoto,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag(takeTag),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.kyc_take_photo), maxLines = 1)
                }
                OutlinedButton(
                    onClick = onPickGallery,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).testTag(galleryTag),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.kyc_pick_gallery), maxLines = 1)
                }
            }

            if (file != null && !analyzingAi) {
                OutlinedButton(
                    onClick = onAnalyzeAi,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(analyzeTag),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.kyc_ai_recheck_button), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(
    checked: Boolean,
    label: String,
    onToggle: () -> Unit,
    tag: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun cameraTempFile(context: Context): File =
    File(context.filesDir, "camera_tmp/capture.jpg").apply { parentFile?.mkdirs() }

private fun cameraTempUri(context: Context): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", cameraTempFile(context))
