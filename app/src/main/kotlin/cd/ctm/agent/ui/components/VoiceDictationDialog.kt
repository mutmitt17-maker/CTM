package cd.ctm.agent.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cd.ctm.agent.R
import cd.ctm.agent.ai.AiOutcome
import cd.ctm.agent.ai.CtmAiAssistant
import cd.ctm.agent.ai.VoiceCustomerExtraction
import kotlinx.coroutines.launch

@Composable
fun VoiceDictationDialog(
    aiAssistant: CtmAiAssistant,
    onExtracted: (VoiceCustomerExtraction) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transcript by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
            if (!spoken.isNullOrBlank()) {
                transcript = if (transcript.isBlank()) spoken else "$transcript $spoken"
                errorMsg = null
            }
        }
    }

    fun launchSpeechRecognition() {
        errorMsg = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-CD")
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.voice_dialog_speech_prompt))
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            errorMsg = context.getString(R.string.voice_dialog_unavailable)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.voice_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.voice_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { launchSpeechRecognition() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice_mic_button"),
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.voice_dialog_start_mic))
                }
                OutlinedTextField(
                    value = transcript,
                    onValueChange = { transcript = it },
                    label = { Text(stringResource(R.string.voice_dialog_transcript_label)) },
                    placeholder = { Text(stringResource(R.string.voice_dialog_placeholder)) },
                    minLines = 3,
                    maxLines = 5,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("voice_transcript_input"),
                )
                if (errorMsg != null) {
                    CtmAlertBanner(AlertKind.WARN, errorMsg!!)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    busy = true
                    errorMsg = null
                    scope.launch {
                        when (val res = aiAssistant.parseCustomerVoiceTranscript(transcript)) {
                            is AiOutcome.Success -> {
                                busy = false
                                onExtracted(res.data)
                            }
                            is AiOutcome.Error -> {
                                busy = false
                                errorMsg = res.message
                            }
                        }
                    }
                },
                enabled = !busy && transcript.isNotBlank(),
                modifier = Modifier.testTag("voice_extract_button"),
            ) {
                if (busy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.voice_dialog_extracting))
                    }
                } else {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.voice_dialog_extract_button))
                }
            }
        },
    )
}
