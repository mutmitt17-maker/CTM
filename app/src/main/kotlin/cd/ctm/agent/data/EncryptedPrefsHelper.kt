package cd.ctm.agent.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * Ouvre un fichier [EncryptedSharedPreferences] en récupérant proprement si la clé Android Keystore
 * a été invalidée (ex: réinstallation ou restauration sur émulateur).
 */
internal fun openEncryptedPrefs(context: Context, fileName: String): SharedPreferences {
    fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        fileName,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    return try {
        create()
    } catch (e: Exception) {
        Log.w("EncryptedPrefs", "Resetting corrupted encrypted prefs $fileName", e)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.deleteSharedPreferences(fileName)
        } else {
            val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/$fileName.xml")
            if (prefsFile.exists()) prefsFile.delete()
        }
        create()
    }
}
