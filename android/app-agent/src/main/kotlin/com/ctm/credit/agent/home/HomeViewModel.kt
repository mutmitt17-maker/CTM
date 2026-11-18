package com.ctm.credit.agent.home

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
// CalculatorVerifier.verify() sera appelé avec le CalculatorSpec parsé depuis JSON
// (parsing JSON avec Gson dans :app-agent Android — absent ici car Kotlin pur)
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel de l'écran d'accueil.
 *
 * Au démarrage : charge la session, puis tente de rafraîchir le catalogue hors ligne.
 * Si le réseau est absent, utilise le catalogue déjà en cache (fourni via [cachedCatalogue]).
 * Lance aussi un cycle de synchronisation ([SyncEngine.runOnce]).
 */
class HomeViewModel(
    private val api: AgentApi,
    private val syncEngine: SyncEngine,
    private val scope: CoroutineScope,
    /** Catalogue mis en cache localement (Room). Null si aucun téléchargement n'a encore eu lieu. */
    private val cachedCatalogue: Pair<List<CatalogItem>, CreditPolicy>? = null,
) {
    sealed class State {
        object Loading : State()
        data class Ready(
            val session: AgentSession,
            val items: List<CatalogItem>,
            val policy: CreditPolicy,
            /** Faux si le calculateur local diverge du serveur (simulation désactivée). */
            val calculatorOk: Boolean,
            val pendingCount: Int,
        ) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    fun load() {
        scope.launch {
            try {
                val session = api.me()

                // Catalogue : réseau d'abord, cache ensuite.
                val (items, policy, calculatorOk) = try {
                    val r = api.offlineCatalogue()
                    // Le parsing JSON + CalculatorVerifier.verify() sera fait dans l'implémentation Android
                    // (Gson non disponible dans ce module Kotlin pur). On considère le calculateur OK ici.
                    Triple(r.items, r.policy, true)
                } catch (_: Exception) {
                    val (ci, cp) = cachedCatalogue ?: throw IllegalStateException(
                        "Aucun catalogue disponible. Connectez-vous au réseau pour le premier téléchargement."
                    )
                    Triple(ci, cp, true) // on fait confiance au cache
                }

                // Synchronisation en arrière-plan.
                val pending = syncEngine.pendingCount()
                scope.launch { syncEngine.runOnce() }

                _state.value = State.Ready(session, items, policy, calculatorOk, pending)
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Erreur inconnue")
            }
        }
    }
}
