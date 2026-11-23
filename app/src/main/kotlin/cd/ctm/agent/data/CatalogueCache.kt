package cd.ctm.agent.data

import android.content.Context
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.google.gson.Gson

/**
 * Dernier catalogue et dernière session reçus du serveur, chiffrés localement pour travailler sans réseau.
 */
class CatalogueCache(context: Context) {
    data class Snapshot(
        val session: AgentSession,
        val items: List<CatalogItem>,
        val policy: CreditPolicy,
        val calculatorOk: Boolean,
    )

    private val legacyPrefs = context.getSharedPreferences("ctm_catalogue_cache", Context.MODE_PRIVATE)
    private val prefs = openEncryptedPrefs(context, "ctm_catalogue_cache_enc")
    private val gson = Gson()

    init {
        val legacyText = legacyPrefs.getString(KEY, null)
        if (legacyText != null && prefs.getString(KEY, null) == null) {
            prefs.edit().putString(KEY, legacyText).apply()
        }
        if (legacyPrefs.contains(KEY)) {
            legacyPrefs.edit().clear().apply()
        }
    }

    fun save(snapshot: Snapshot) {
        prefs.edit().putString(KEY, gson.toJson(snapshot)).apply()
    }

    fun load(): Snapshot? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            gson.fromJson(text, Snapshot::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY = "snapshot"
    }
}
