package cd.ctm.agent.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.ctm.credit.security.DeviceFingerprint
import java.security.MessageDigest

/** Empreinte stable du téléphone : 64 caractères hexadécimaux (SHA-256), envoyée au serveur à chaque appel. */
class AndroidDeviceFingerprint(context: Context) : DeviceFingerprint {
    private val value: String by lazy {
        try {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
            val raw = listOf(androidId, Build.MANUFACTURER, Build.MODEL).joinToString("|")
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            java.util.UUID.randomUUID().toString().replace("-", "").padEnd(64, '0')
        }
    }

    override fun get(): String = value
}
