package com.ctm.credit.agent.home

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.model.CalculatorVerifier
import com.ctm.credit.model.VerificationResult
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.CancellationException
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
    /**
     * Résultat de la vérification du calculateur au moment où le catalogue en cache a été téléchargé.
     * Faux par défaut : sans cette information, la simulation hors ligne reste désactivée (sécurité).
     */
    private val cachedCalculatorOk: Boolean = false,
    /** Dernière session connue (nom, agence). Sert quand le réseau est coupé au démarrage. */
    private val cachedSession: AgentSession? = null,
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
                val session = try {
                    api.me()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cachedSession ?: throw e
                }

                // Catalogue : réseau d'abord, cache ensuite.
                val (items, policy, calculatorOk) = try {
                    val r = api.offlineCatalogue()
                    // Un seul écart avec les vecteurs du serveur désactive la simulation hors ligne.
                    val ok = CalculatorVerifier.verify(r.calculatorSpec) is VerificationResult.Ok
                    Triple(r.items, r.policy, ok)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    val (ci, cp) = cachedCatalogue ?: throw IllegalStateException(
                        "Aucun catalogue disponible. Connectez-vous au réseau pour le premier téléchargement."
                    )
                    Triple(ci, cp, cachedCalculatorOk)
                }

                // Synchronisation en arrière-plan.
                val pending = syncEngine.pendingCount()
                scope.launch { syncEngine.runOnce() }

                _state.value = State.Ready(session, items, policy, calculatorOk, pending)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Erreur inconnue")
            }
        }
    }
}
