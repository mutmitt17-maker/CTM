package cd.ctm.agent.ui.components

import androidx.camera.mlkit.vision.MlKitAnalyzer
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cd.ctm.agent.R
import com.ctm.credit.model.Imei
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Scanner caméra temps réel (CameraX + ML Kit) détectant un IMEI valide à 15 chiffres
 * par code-barres/QR OU en secours par lecture OCR (écran `*#06#` ou étiquette).
 */
@Composable
fun ImeiScannerDialog(
    onDetected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var detected by remember { mutableStateOf(false) }
    var lastOcrCandidate by remember { mutableStateOf<String?>(null) }
    var ocrMatchCount by remember { mutableStateOf(0) }
    var statusHint by remember { mutableStateOf<String?>(null) }

    val barcodeScanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
            .build()
        BarcodeScanning.getClient(options)
    }

    val textRecognizer = remember {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    val controller = remember(context) {
        val executor = ContextCompat.getMainExecutor(context)
        LifecycleCameraController(context).apply {
            @Suppress("DEPRECATION")
            setImageAnalysisAnalyzer(
                executor,
                MlKitAnalyzer(
                    listOf(barcodeScanner, textRecognizer),
                    CameraController.COORDINATE_SYSTEM_VIEW_REFERENCED,
                    executor,
                ) { result ->
                    if (detected) return@MlKitAnalyzer

                    // 1. Priorité au code-barres / QR (détection immédiate).
                    val barcodes = result?.getValue(barcodeScanner).orEmpty()
                    for (b in barcodes) {
                        val raw = b.rawValue ?: continue
                        val candidate = extractValidImei(raw)
                        if (candidate != null) {
                            detected = true
                            onDetected(candidate)
                            return@MlKitAnalyzer
                        }
                    }

                    // 2. Secours OCR hors ligne (ex. écran *#06# ou texte imprimé sans code-barres).
                    val ocrText = result?.getValue(textRecognizer)?.text
                    if (!ocrText.isNullOrBlank()) {
                        val ocrCandidate = extractValidImei(ocrText)
                        if (ocrCandidate != null) {
                            if (ocrCandidate == lastOcrCandidate) {
                                ocrMatchCount++
                            } else {
                                lastOcrCandidate = ocrCandidate
                                ocrMatchCount = 1
                            }
                            statusHint = "OCR : $ocrCandidate"
                            if (ocrMatchCount >= 2) {
                                detected = true
                                onDetected(ocrCandidate)
                            }
                        }
                    }
                },
            )
        }
    }

    DisposableEffect(lifecycleOwner, controller) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            barcodeScanner.close()
            textRecognizer.close()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delivery_scanner_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.delivery_scanner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            this.controller = controller
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                if (statusHint != null) {
                    CtmBadge(statusHint!!, BadgeTone.INFO)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun extractValidImei(raw: String): String? {
    val lines = raw.lineSequence()
    for (line in lines) {
        val compact = line.replace(Regex("[\\s-]"), "")
        val match = Regex("\\d{15}").findAll(compact)
            .map { it.value }
            .firstOrNull { Imei.isValid(it) }
        if (match != null) return match
    }
    val fullCompact = raw.replace(Regex("[\\s-]"), "")
    return Regex("\\d{15}").findAll(fullCompact)
        .map { it.value }
        .firstOrNull { Imei.isValid(it) }
}
