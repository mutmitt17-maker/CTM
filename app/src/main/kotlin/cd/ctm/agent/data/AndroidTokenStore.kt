package cd.ctm.agent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ctm.credit.security.TokenStore

/** Jetons de connexion et identité de l'agent actif, chiffrés avec Android Keystore. */
class AndroidTokenStore(context: Context) : TokenStore {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "ctm_secure_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun accessToken(): String? = prefs.getString(ACCESS, null)

    override fun refreshToken(): String? = prefs.getString(REFRESH, null)

    fun currentAgentId(): String? = prefs.getString(AGENT_ID, null)

    fun setCurrentAgentId(agentId: String?) {
        prefs.edit().apply {
            if (agentId.isNullOrBlank()) remove(AGENT_ID) else putString(AGENT_ID, agentId)
        }.apply()
    }

    override fun save(accessToken: String, refreshToken: String) {
        prefs.edit().putString(ACCESS, accessToken).putString(REFRESH, refreshToken).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val ACCESS = "access_token"
        const val REFRESH = "refresh_token"
        const val AGENT_ID = "agent_user_id"
    }
}
