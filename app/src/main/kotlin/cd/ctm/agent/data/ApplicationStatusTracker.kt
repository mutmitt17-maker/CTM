package cd.ctm.agent.data

import android.content.Context
import android.content.SharedPreferences
import com.ctm.credit.http.AgentApplicationSummary
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Suit localement le dernier statut connu des dossiers de l'agent afin de détecter
 * les transitions côté serveur (worker-biometrics, resolve_review) et déclencher une alerte.
 */
class ApplicationStatusTracker(context: Context) {

    private val prefs: SharedPreferences = openEncryptedPrefs(context, FILE_NAME)

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    /**
     * Compare les statuts reçus avec le dernier instantané connu pour [agentId].
     * Au premier appel (instantané vide), mémorise les statuts sans alerter pour éviter le bruit initial.
     */
    @Synchronized
    fun detectTransitions(
        agentId: String,
        apps: List<AgentApplicationSummary>,
    ): List<AgentApplicationSummary> {
        if (agentId.isBlank() || apps.isEmpty()) return emptyList()
        val key = "$KEY_PREFIX$agentId"
        val raw = prefs.getString(key, null)
        val previous: Map<String, String> = if (raw.isNullOrBlank()) {
            emptyMap()
        } else {
            runCatching { gson.fromJson<Map<String, String>>(raw, mapType) }.getOrNull().orEmpty()
        }

        val updated = LinkedHashMap<String, String>(previous)
        val transitions = mutableListOf<AgentApplicationSummary>()

        for (app in apps) {
            if (app.id.isBlank() || app.status.isBlank()) continue
            val prevStatus = previous[app.id]
            if (raw != null && prevStatus != null && prevStatus != app.status &&
                app.status in ALERT_TARGET_STATUSES
            ) {
                transitions += app
            } else if (raw != null && prevStatus == null && app.status in ALERT_TARGET_STATUSES) {
                transitions += app
            }
            updated[app.id] = app.status
        }

        // Conserve les 100 derniers dossiers suivis.
        val trimmed = if (updated.size > 100) {
            updated.entries.toList().takeLast(100).associate { it.key to it.value }
        } else {
            updated
        }
        prefs.edit().putString(key, gson.toJson(trimmed)).apply()
        return transitions
    }

    private companion object {
        const val FILE_NAME = "ctm_app_status_tracker_enc"
        const val KEY_PREFIX = "agent_apps_"
        val ALERT_TARGET_STATUSES = setOf("approved", "pending_review", "rejected")
    }
}
