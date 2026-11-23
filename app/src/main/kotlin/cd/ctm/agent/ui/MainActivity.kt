package cd.ctm.agent.ui

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import cd.ctm.agent.AppContainer
import cd.ctm.agent.CtmApplication
import cd.ctm.agent.R
import cd.ctm.agent.data.AndroidBiometricGate
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.theme.CtmTheme
import com.ctm.credit.security.BiometricGate
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private lateinit var container: AppContainer
    private lateinit var biometricGate: AndroidBiometricGate

    private var biometricLocked by mutableStateOf(false)
    private var lastStoppedAtMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Empêche les captures d'écran et l'exposition de données clients/KYC dans le sélecteur d'apps.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        enableEdgeToEdge()

        container = (application as CtmApplication).container
        biometricGate = AndroidBiometricGate(this)

        if (savedInstanceState == null && container.tokens.isLoggedIn() && biometricGate.isAvailable()) {
            biometricLocked = true
        } else if (savedInstanceState != null) {
            biometricLocked = savedInstanceState.getBoolean(KEY_BIOMETRIC_LOCKED, false)
            lastStoppedAtMs = savedInstanceState.getLong(KEY_LAST_STOPPED_AT, 0L)
        }

        setContent {
            CtmTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (biometricLocked && container.tokens.isLoggedIn()) {
                        BiometricLockScreen(
                            gate = biometricGate,
                            onUnlocked = { biometricLocked = false },
                            onLogout = {
                                container.logout()
                                biometricLocked = false
                            },
                        )
                    } else {
                        CtmNavApp(container)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (lastStoppedAtMs > 0L && container.tokens.isLoggedIn() && biometricGate.isAvailable()) {
            val elapsed = SystemClock.elapsedRealtime() - lastStoppedAtMs
            if (elapsed > LOCK_AFTER_BACKGROUND_MS) {
                biometricLocked = true
            }
        }
        lastStoppedAtMs = 0L
    }

    override fun onStop() {
        super.onStop()
        lastStoppedAtMs = SystemClock.elapsedRealtime()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_BIOMETRIC_LOCKED, biometricLocked)
        outState.putLong(KEY_LAST_STOPPED_AT, lastStoppedAtMs)
    }

    private companion object {
        const val LOCK_AFTER_BACKGROUND_MS = 30_000L
        const val KEY_BIOMETRIC_LOCKED = "biometric_locked"
        const val KEY_LAST_STOPPED_AT = "last_stopped_at"
    }
}

@Composable
private fun BiometricLockScreen(
    gate: BiometricGate,
    onUnlocked: () -> Unit,
    onLogout: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val promptTitle = stringResource(R.string.biometric_prompt_title)
    val promptSubtitle = stringResource(R.string.biometric_prompt_subtitle)
    val failedMsg = stringResource(R.string.biometric_failed_msg)

    fun triggerAuth() {
        if (busy) return
        busy = true
        errorMessage = null
        scope.launch {
            when (gate.authenticate(promptTitle, promptSubtitle)) {
                is BiometricGate.Result.Success,
                is BiometricGate.Result.Unavailable -> onUnlocked()
                is BiometricGate.Result.Cancelled,
                is BiometricGate.Result.Failed -> errorMessage = failedMsg
            }
            busy = false
        }
    }

    LaunchedEffect(Unit) {
        triggerAuth()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CtmCard(modifier = Modifier.widthIn(max = 420.dp)) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(R.string.biometric_lock_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.biometric_lock_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (errorMessage != null) {
                    CtmAlertBanner(AlertKind.WARN, errorMessage!!)
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = { triggerAuth() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("biometric_unlock_button"),
                ) {
                    Text(stringResource(R.string.biometric_unlock_button))
                }
                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.testTag("biometric_logout_button"),
                ) {
                    Text(stringResource(R.string.logout_button))
                }
            }
        }
    }
}
