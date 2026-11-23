package cd.ctm.agent.data

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.ctm.credit.security.BiometricGate
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Implémentation Android de [BiometricGate] via `BiometricPrompt`
 * (empreinte, reconnaissance faciale ou code/schéma de l'appareil).
 */
class AndroidBiometricGate(private val activity: FragmentActivity) : BiometricGate {
    private val authenticators = BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    override fun isAvailable(): Boolean {
        val status = BiometricManager.from(activity).canAuthenticate(authenticators)
        return status == BiometricManager.BIOMETRIC_SUCCESS
    }

    override suspend fun authenticate(title: String, subtitle: String): BiometricGate.Result =
        suspendCancellableCoroutine { cont ->
            if (!isAvailable()) {
                cont.resume(BiometricGate.Result.Unavailable)
                return@suspendCancellableCoroutine
            }

            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (cont.isActive) cont.resume(BiometricGate.Result.Success(UUID.randomUUID().toString()))
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!cont.isActive) return
                        when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                            BiometricPrompt.ERROR_CANCELED -> cont.resume(BiometricGate.Result.Cancelled)
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> cont.resume(BiometricGate.Result.Failed(locked = true))
                            BiometricPrompt.ERROR_NO_BIOMETRICS,
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE -> cont.resume(BiometricGate.Result.Unavailable)
                            else -> cont.resume(BiometricGate.Result.Failed(locked = false))
                        }
                    }

                    override fun onAuthenticationFailed() {
                        // L'utilisateur peut réessayer tant que le dialogue système est ouvert.
                    }
                },
            )

            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(authenticators)
                .build()

            prompt.authenticate(info)
            cont.invokeOnCancellation { runCatching { prompt.cancelAuthentication() } }
        }
}
