package cd.ctm.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cd.ctm.agent.AppConfig
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ai.AiOutcome
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.BadgeTone
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmBadge
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmKeyValueRow
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.fmtDateTime
import com.ctm.credit.agent.ApiResult
import kotlinx.coroutines.launch
import java.time.Instant

@Composable
fun IntegrationsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lastSyncAtMs by container.lastSyncAtMs.collectAsState()
    val pending by container.pending.collectAsState()
    val pendingPhotos by container.pendingPhotos.collectAsState()

    var testingSupabase by remember { mutableStateOf(false) }
    var supabaseResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    var testingAi by remember { mutableStateOf(false) }
    var aiResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    CtmScreenContainer(
        title = stringResource(R.string.integrations_title),
        subtitle = stringResource(R.string.integrations_subtitle),
        onBack = onBack,
    ) {
        CtmCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.integrations_supabase_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                CtmBadge(
                    label = when (supabaseResult?.first) {
                        true -> "En ligne"
                        false -> "Hors ligne"
                        null -> "À tester"
                    },
                    tone = when (supabaseResult?.first) {
                        true -> BadgeTone.OK
                        false -> BadgeTone.BAD
                        null -> BadgeTone.INFO
                    },
                )
            }
            CtmKeyValueRow("Hôte", AppConfig.SUPABASE_URL.removePrefix("https://"), mono = true)
            CtmKeyValueRow(
                "Dernière sync",
                if (lastSyncAtMs > 0L) fmtDateTime(Instant.ofEpochMilli(lastSyncAtMs).toString()) else "Session active",
            )
            CtmKeyValueRow("File locale", "$pending action(s) · $pendingPhotos photo(s)", mono = true)

            if (supabaseResult != null) {
                CtmAlertBanner(
                    if (supabaseResult!!.first) AlertKind.OK else AlertKind.ERROR,
                    supabaseResult!!.second,
                )
            }

            OutlinedButton(
                onClick = {
                    testingSupabase = true
                    supabaseResult = null
                    scope.launch {
                        supabaseResult = when (val res = container.agentHttpApi.pingServerLatencyMs()) {
                            is ApiResult.Ok -> true to "Backend joignable (${res.value} ms)."
                            is ApiResult.Failure -> false to res.message
                        }
                        testingSupabase = false
                    }
                },
                enabled = !testingSupabase,
                modifier = Modifier.fillMaxWidth().testTag("integrations_ping_supabase_button"),
            ) {
                if (testingSupabase) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.loading_default))
                } else {
                    Icon(Icons.Outlined.CloudDone, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.integrations_test_supabase))
                }
            }
        }

        CtmCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.integrations_ai_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                CtmBadge(
                    label = when (aiResult?.first) {
                        true -> "Gemini OK"
                        false -> "Erreur IA"
                        null -> "App Check"
                    },
                    tone = when (aiResult?.first) {
                        true -> BadgeTone.OK
                        false -> BadgeTone.WARN
                        null -> BadgeTone.INFO
                    },
                )
            }
            Text(
                text = stringResource(R.string.integrations_ai_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (aiResult != null) {
                CtmAlertBanner(
                    if (aiResult!!.first) AlertKind.OK else AlertKind.WARN,
                    aiResult!!.second,
                )
            }
            OutlinedButton(
                onClick = {
                    testingAi = true
                    aiResult = null
                    scope.launch {
                        aiResult = when (val res = container.aiAssistant.probeConnection()) {
                            is AiOutcome.Success -> true to "Firebase AI Logic opérationnel (réponse : ${res.data})."
                            is AiOutcome.Error -> false to res.message
                        }
                        testingAi = false
                    }
                },
                enabled = !testingAi,
                modifier = Modifier.fillMaxWidth().testTag("integrations_test_ai_button"),
            ) {
                if (testingAi) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.loading_default))
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.integrations_test_ai))
                }
            }
        }

        CtmCard {
            Text(
                stringResource(R.string.integrations_device_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            CtmKeyValueRow("Base locale SQLCipher", "Chiffrée AES-256 (KeyStore)")
            CtmKeyValueRow("Sync arrière-plan", "WorkManager 15 min + réseau")
            CtmKeyValueRow("Scanner IMEI", "Code-barres + OCR hors ligne")
            CtmKeyValueRow("Permission Caméra", if (cameraGranted) "Accordée" else "À demander à l'usage")
            CtmKeyValueRow("Notifications dossiers", if (notifGranted) "Activées" else "Non autorisées")
        }

        CtmCard {
            Text(
                stringResource(R.string.integrations_server_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.integrations_server_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CtmKeyValueRow("Biométrie serveur", "worker-biometrics (BIOMETRICS_MODE)")
            CtmKeyValueRow("MDM verrouillage", "worker-device-commands (MDM_MODE)")
            CtmKeyValueRow("Mobile Money RDC", "Webhook M-Pesa / Airtel / Orange")
            CtmKeyValueRow("E-mails Auth", "SMTP personnalisé Supabase")
            CtmKeyValueRow("Alertes dossiers", "Surveillance sync + FCM optionnel")
        }
    }
}
