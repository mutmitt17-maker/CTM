package cd.ctm.agent

import android.content.Context
import cd.ctm.agent.data.AndroidDeviceFingerprint
import cd.ctm.agent.data.AndroidTokenStore
import cd.ctm.agent.data.CatalogueCache
import cd.ctm.agent.data.SqliteSyncStore
import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.agent.application.ApplicationViewModel
import com.ctm.credit.agent.customer.CustomerViewModel
import com.ctm.credit.agent.home.HomeViewModel
import com.ctm.credit.agent.login.LoginViewModel
import com.ctm.credit.agent.payment.PaymentViewModel
import com.ctm.credit.http.HttpAgentApi
import com.ctm.credit.http.HttpConfig
import com.ctm.credit.http.HttpSyncApi
import com.ctm.credit.http.SupabaseHttp
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.network.SyncOutcome
import com.ctm.credit.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Assemble les pièces de l'application (injection « à la main », sans bibliothèque).
 * Les ViewModels tournent sur le fil d'entrée-sortie : le réseau et la base ne bloquent jamais l'écran.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tokens: TokenStore = AndroidTokenStore(context)
    private val fingerprint = AndroidDeviceFingerprint(context)
    private val http = SupabaseHttp(HttpConfig(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY), tokens, fingerprint)
    val api: AgentApi = HttpAgentApi(http)
    private val store = SqliteSyncStore(context)
    val syncEngine = SyncEngine(store, HttpSyncApi(http))
    val cache = CatalogueCache(context)

    private val _pending = MutableStateFlow(0)
    /** Nombre d'actions encore à envoyer au serveur. */
    val pending: StateFlow<Int> = _pending

    private val _syncMessage = MutableStateFlow("")
    /** Résultat de la dernière synchronisation, en français. */
    val syncMessage: StateFlow<String> = _syncMessage

    fun newLoginViewModel() = LoginViewModel(api, tokens, appScope)

    fun newHomeViewModel(): HomeViewModel {
        val snapshot = cache.load()
        return HomeViewModel(
            api, syncEngine, appScope,
            cachedCatalogue = snapshot?.let { it.items to it.policy },
            cachedCalculatorOk = snapshot?.calculatorOk ?: false,
            cachedSession = snapshot?.session,
        )
    }

    fun newCustomerViewModel() = CustomerViewModel(syncEngine, appScope)

    fun newPaymentViewModel() = PaymentViewModel(syncEngine, appScope)

    fun newApplicationViewModel(policy: CreditPolicy) = ApplicationViewModel(syncEngine, policy, appScope)

    fun refreshPending() {
        appScope.launch { _pending.value = syncEngine.pendingCount() }
    }

    /** Envoie la file au serveur. [onNeedsLogin] est appelé si la session est terminée. */
    fun syncNow(onNeedsLogin: () -> Unit) {
        appScope.launch {
            _syncMessage.value = "Synchronisation en cours…"
            val report = try {
                syncEngine.runOnce()
            } catch (e: Exception) {
                _syncMessage.value = "Synchronisation impossible : ${e.message ?: "erreur inconnue"}."
                return@launch
            }
            _pending.value = syncEngine.pendingCount()
            _syncMessage.value = when (report.outcome) {
                SyncOutcome.DONE -> "Terminé : ${report.applied} envoyée(s), ${report.conflicts} en conflit, ${report.failed} refusée(s)."
                SyncOutcome.RETRY_LATER -> "Pas de connexion au serveur. Réessayez plus tard."
                SyncOutcome.NEEDS_LOGIN -> "Session expirée. Reconnectez-vous."
                SyncOutcome.REFUSED -> "Le serveur a refusé l'envoi. Contactez l'administrateur."
                SyncOutcome.INCOMPLETE -> "Envoi interrompu par précaution. Réessayez."
            }
            if (report.outcome == SyncOutcome.NEEDS_LOGIN) onNeedsLogin()
        }
    }

    /** Déconnexion : efface les jetons et le catalogue en cache. La file des actions à envoyer est conservée. */
    fun logout() {
        tokens.clear()
        cache.clear()
        _syncMessage.value = ""
    }
}
