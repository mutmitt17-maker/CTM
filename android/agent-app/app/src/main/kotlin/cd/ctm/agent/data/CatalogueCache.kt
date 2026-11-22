package cd.ctm.agent.data

import android.content.Context
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.google.gson.Gson

/**
 * Dernier catalogue et dernière session reçus du serveur, pour travailler sans réseau.
 * Contient des prix et le nom de l'agence, pas de données de clients ni de jetons.
 */
class CatalogueCache(context: Context) {
    data class Snapshot(
        val session: AgentSession,
        val items: List<CatalogItem>,
        val policy: CreditPolicy,
        /** Résultat de la vérification du calculateur au moment du téléchargement. */
        val calculatorOk: Boolean,
    )

    private val prefs = context.getSharedPreferences("ctm_catalogue_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(snapshot: Snapshot) {
        prefs.edit().putString(KEY, gson.toJson(snapshot)).apply()
    }

    fun load(): Snapshot? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            gson.fromJson(text, Snapshot::class.java)
        } catch (e: Exception) {
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
