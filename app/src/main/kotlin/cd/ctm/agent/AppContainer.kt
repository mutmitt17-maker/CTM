package cd.ctm.agent

import android.content.Context
import cd.ctm.agent.data.AndroidDeviceFingerprint
import cd.ctm.agent.data.AndroidPhotoVault
import cd.ctm.agent.data.AndroidTokenStore
import cd.ctm.agent.data.CatalogueCache
import cd.ctm.agent.data.SqliteSyncStore
import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.agent.application.ApplicationViewModel
import com.ctm.credit.agent.customer.CustomerViewModel
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.agent.login.LoginViewModel
import com.ctm.credit.agent.payment.PaymentViewModel
import com.ctm.credit.client.ClientApi
import com.ctm.credit.client.contract.ContractViewModel
import com.ctm.credit.client.pay.PayInstructionsViewModel
import com.ctm.credit.http.HttpAgentApi
import com.ctm.credit.http.HttpBackOfficeApi
import com.ctm.credit.http.HttpClientApi
import com.ctm.credit.http.HttpConfig
import com.ctm.credit.http.HttpSyncApi
import com.ctm.credit.http.SupabaseHttp
import com.ctm.credit.model.CalculatorVerifier
import com.ctm.credit.model.VerificationResult
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.network.SyncItem
import com.ctm.credit.network.SyncOutcome
import com.ctm.credit.security.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Conteneur d'injection de dépendances de l'application CTM.
 * Rassemble la sécurité locale, le réseau Supabase, le moteur de synchronisation hors ligne,
 * les API agent / back-office / client et la file durable SQLite.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tokens: TokenStore = AndroidTokenStore(context)
    val fingerprint = AndroidDeviceFingerprint(context)
    val photoVault = AndroidPhotoVault(context)
    private val http = SupabaseHttp(HttpConfig(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY), tokens, fingerprint)
    val api: AgentApi = HttpAgentApi(http)
    val backOffice = HttpBackOfficeApi(http)
    val clientApi: ClientApi = HttpClientApi(http)
    val syncStore = SqliteSyncStore(context)
    val syncEngine = SyncEngine(syncStore, HttpSyncApi(http))
    val cache = CatalogueCache(context)

    sealed class DashboardState {
        data object Loading : DashboardState()
        data class Ready(
            val session: AgentSession,
            val items: List<CatalogItem>,
            val policy: CreditPolicy?,
            val calculatorOk: Boolean,
            val catalogueError: String? = null,
            val fromCache: Boolean = false,
        ) : DashboardState()
        data class Error(val message: String) : DashboardState()
    }

    private val _dashboard = MutableStateFlow<DashboardState>(DashboardState.Loading)
    val dashboard: StateFlow<DashboardState> = _dashboard

    private val _pending = MutableStateFlow(0)
    val pending: StateFlow<Int> = _pending

    private val _syncItems = MutableStateFlow<List<SyncItem>>(emptyList())
    val syncItems: StateFlow<List<SyncItem>> = _syncItems

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning

    fun newLoginViewModel() = LoginViewModel(api, tokens, appScope)
    fun newCustomerViewModel() = CustomerViewModel(syncEngine, appScope)
    fun newPaymentViewModel() = PaymentViewModel(syncEngine, appScope)
    fun newApplicationViewModel(policy: CreditPolicy) = ApplicationViewModel(syncEngine, policy, appScope)
    fun newDeliveryViewModel() = DeliveryViewModel(api, fingerprint, appScope)
    fun newContractViewModel() = ContractViewModel(clientApi, appScope)
    fun newPayInstructionsViewModel() = PayInstructionsViewModel(clientApi, appScope)

    fun loadDashboard() {
        _dashboard.value = DashboardState.Loading
        appScope.launch {
            val snapshot = cache.load()
            try {
                val session = try {
                    api.me()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snapshot?.session ?: throw e
                }

                var items: List<CatalogItem> = emptyList()
                var policy: CreditPolicy? = null
                var calculatorOk = false
                var catError: String? = null
                var fromCache = false

                try {
                    val r = api.offlineCatalogue()
                    val ok = CalculatorVerifier.verify(r.calculatorSpec) is VerificationResult.Ok
                    items = r.items
                    policy = r.policy
                    calculatorOk = ok
                    cache.save(CatalogueCache.Snapshot(session, items, r.policy, ok))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (snapshot != null) {
                        items = snapshot.items
                        policy = snapshot.policy
                        calculatorOk = snapshot.calculatorOk
                        fromCache = true
                    } else {
                        catError = e.message
                    }
                }

                refreshQueueState()
                _dashboard.value = DashboardState.Ready(
                    session = session,
                    items = items,
                    policy = policy,
                    calculatorOk = calculatorOk,
                    catalogueError = catError,
                    fromCache = fromCache,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _dashboard.value = DashboardState.Error(e.message ?: "Impossible de charger la session.")
            }
        }
    }

    fun refreshQueueState() {
        appScope.launch {
            _pending.value = syncEngine.pendingCount()
            _syncItems.value = syncStore.listAll()
        }
    }

    fun syncNow(onNeedsLogin: () -> Unit) {
        if (_syncRunning.value) return
        appScope.launch {
            _syncRunning.value = true
            _syncMessage.value = "Synchronisation en cours…"
            try {
                val report = try {
                    syncEngine.runOnce()
                } catch (e: Exception) {
                    _syncMessage.value = "Synchronisation impossible : ${e.message ?: "erreur inconnue"}."
                    return@launch
                }
                _pending.value = syncEngine.pendingCount()
                _syncItems.value = syncStore.listAll()
                _syncMessage.value = when (report.outcome) {
                    SyncOutcome.DONE -> "Terminé : ${report.applied} envoyée(s), ${report.conflicts} en conflit, ${report.failed} refusée(s)."
                    SyncOutcome.RETRY_LATER -> "Pas de connexion au serveur. Réessayez plus tard."
                    SyncOutcome.NEEDS_LOGIN -> "Session expirée. Reconnectez-vous."
                    SyncOutcome.REFUSED -> "Le serveur a refusé l'envoi. Contactez l'administrateur."
                    SyncOutcome.INCOMPLETE -> "Envoi interrompu par précaution. Réessayez."
                }
                if (report.outcome == SyncOutcome.NEEDS_LOGIN) onNeedsLogin()
            } finally {
                _syncRunning.value = false
            }
        }
    }

    fun correctAndRetry(old: SyncItem, newPayloadJson: String) {
        appScope.launch {
            val (replacement, supersededOld) = SyncEngine.correct(
                old = old,
                newPayloadJson = newPayloadJson,
                newLocalId = syncStore.nextLocalId(),
                now = Instant.now(),
            )
            syncStore.save(supersededOld)
            syncStore.save(replacement)
            _pending.value = syncEngine.pendingCount()
            _syncItems.value = syncStore.listAll()
            _syncMessage.value = "Action corrigée et remise en file d'envoi."
        }
    }

    fun logout() {
        tokens.clear()
        cache.clear()
        _syncMessage.value = ""
        _dashboard.value = DashboardState.Loading
    }
}
