package cd.ctm.agent

import android.content.Context
import cd.ctm.agent.data.AndroidDeviceFingerprint
import cd.ctm.agent.data.AndroidPhotoVault
import cd.ctm.agent.data.AndroidTokenStore
import cd.ctm.agent.data.CatalogueCache
import cd.ctm.agent.data.SqliteSyncStore
import com.ctm.credit.agent.AgentApi
import com.ctm.credit.agent.AgentSession
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.CatalogItem
import com.ctm.credit.agent.CreditPolicy
import com.ctm.credit.agent.application.ApplicationViewModel
import com.ctm.credit.agent.customer.CustomerViewModel
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.agent.kyc.KycViewModel
import com.ctm.credit.agent.login.LoginViewModel
import com.ctm.credit.agent.payment.PaymentViewModel
import com.ctm.credit.http.HttpAgentApi
import com.ctm.credit.http.HttpConfig
import com.ctm.credit.http.HttpSyncApi
import com.ctm.credit.http.SupabaseHttp
import com.ctm.credit.model.CalculatorVerifier
import com.ctm.credit.model.VerificationResult
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.network.SyncItem
import com.ctm.credit.network.SyncOutcome
import com.ctm.credit.security.TokenStore
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Conteneur d'injection de dépendances de l'application Agent CTM.
 * Conservé au niveau [android.app.Application] afin de préserver les ViewModels
 * et l'état des flux lors des rotations d'écran.
 */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tokens: TokenStore = AndroidTokenStore(context)
    val fingerprint = AndroidDeviceFingerprint(context)
    val photoVault = AndroidPhotoVault(context)
    private val http = SupabaseHttp(HttpConfig(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY), tokens, fingerprint)
    val agentHttpApi = HttpAgentApi(http)
    val api: AgentApi = agentHttpApi
    val syncStore = SqliteSyncStore(context)
    val syncEngine = SyncEngine(syncStore, HttpSyncApi(http), canSend = ::canSendSyncItem)
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

    private val _pendingPhotos = MutableStateFlow(0)
    val pendingPhotos: StateFlow<Int> = _pendingPhotos

    private val _syncItems = MutableStateFlow<List<SyncItem>>(emptyList())
    val syncItems: StateFlow<List<SyncItem>> = _syncItems

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning

    @Volatile private var _loginVm: LoginViewModel? = null
    @Volatile private var _customerVm: CustomerViewModel? = null
    @Volatile private var _kycVm: KycViewModel? = null
    @Volatile private var _paymentVm: PaymentViewModel? = null
    @Volatile private var _deliveryVm: DeliveryViewModel? = null
    @Volatile private var _applicationVm: ApplicationViewModel? = null
    @Volatile private var _applicationPolicy: CreditPolicy? = null

    fun loginViewModel(): LoginViewModel =
        _loginVm ?: LoginViewModel(api, tokens, appScope).also { _loginVm = it }

    fun customerViewModel(): CustomerViewModel =
        _customerVm ?: CustomerViewModel(syncEngine, appScope).also { _customerVm = it }

    fun kycViewModel(): KycViewModel =
        _kycVm ?: KycViewModel(syncEngine, appScope).also { _kycVm = it }

    fun paymentViewModel(): PaymentViewModel =
        _paymentVm ?: PaymentViewModel(syncEngine, appScope).also { _paymentVm = it }

    fun deliveryViewModel(): DeliveryViewModel =
        _deliveryVm ?: DeliveryViewModel(api, fingerprint, appScope).also { _deliveryVm = it }

    fun applicationViewModel(policy: CreditPolicy): ApplicationViewModel {
        val current = _applicationVm
        if (current != null && _applicationPolicy == policy) return current
        return ApplicationViewModel(syncEngine, policy, appScope).also {
            _applicationVm = it
            _applicationPolicy = policy
        }
    }

    /**
     * Empêche l'envoi de `kyc.submit` tant que la pièce d'identité ou le selfie est encore
     * dans `photoVault` en attente d'upload vers le bucket `kyc`.
     */
    private fun canSendSyncItem(item: SyncItem): Boolean {
        if (item.action != SyncAction.KYC_SUBMIT) return true
        val pendingSet = photoVault.pending().toSet()
        if (pendingSet.isEmpty()) return true
        return runCatching {
            val obj = JsonParser.parseString(item.payloadJson).asJsonObject
            val idPath = obj.get("id_document_photo_path")?.takeIf { !it.isJsonNull }?.asString
            val selfiePath = obj.get("selfie_path")?.takeIf { !it.isJsonNull }?.asString
            val idWaiting = !idPath.isNullOrBlank() && "kyc/$idPath" in pendingSet
            val selfieWaiting = !selfiePath.isNullOrBlank() && "kyc/$selfiePath" in pendingSet
            !idWaiting && !selfieWaiting
        }.getOrDefault(true)
    }

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
            _pendingPhotos.value = photoVault.pending().size
            _syncItems.value = syncStore.listAll()
        }
    }

    fun syncNow(onNeedsLogin: () -> Unit) {
        if (_syncRunning.value) return
        appScope.launch {
            _syncRunning.value = true
            _syncMessage.value = "Synchronisation en cours…"
            try {
                var totalApplied = 0
                var totalConflicts = 0
                var totalFailed = 0
                var uploadedPhotos = 0
                var photoError: String? = null
                var finalOutcome: SyncOutcome

                while (true) {
                    val report = try {
                        syncEngine.runOnce()
                    } catch (e: Exception) {
                        _syncMessage.value = "Synchronisation impossible : ${e.message ?: "erreur inconnue"}."
                        return@launch
                    }
                    totalApplied += report.applied
                    totalConflicts += report.conflicts
                    totalFailed += report.failed
                    finalOutcome = report.outcome

                    if (report.outcome != SyncOutcome.DONE) break

                    val (uploadedNow, err) = uploadEligiblePhotos()
                    uploadedPhotos += uploadedNow
                    if (err != null) {
                        photoError = err
                        break
                    }
                    if (uploadedNow == 0) break
                }

                _pending.value = syncEngine.pendingCount()
                _pendingPhotos.value = photoVault.pending().size
                _syncItems.value = syncStore.listAll()

                _syncMessage.value = when {
                    finalOutcome == SyncOutcome.NEEDS_LOGIN -> "Session expirée. Reconnectez-vous."
                    finalOutcome == SyncOutcome.RETRY_LATER -> "Pas de connexion au serveur. Réessayez plus tard."
                    finalOutcome == SyncOutcome.REFUSED -> "Le serveur a refusé l'envoi. Contactez l'administrateur."
                    finalOutcome == SyncOutcome.INCOMPLETE -> "Envoi interrompu par précaution. Réessayez."
                    photoError != null -> "Actions envoyées : $totalApplied, photos envoyées : $uploadedPhotos. Échec photo : $photoError"
                    else -> {
                        val photoSuffix = if (uploadedPhotos > 0) ", $uploadedPhotos photo(s) envoyée(s)" else ""
                        "Terminé : $totalApplied envoyée(s)$photoSuffix, $totalConflicts en conflit, $totalFailed refusée(s)."
                    }
                }
                if (finalOutcome == SyncOutcome.NEEDS_LOGIN) onNeedsLogin()
            } finally {
                _syncRunning.value = false
            }
        }
    }

    /**
     * Envoie vers Supabase Storage les photos KYC dont le client (`customer.create`)
     * existe déjà côté serveur, puis les efface du stockage local.
     */
    private suspend fun uploadEligiblePhotos(): Pair<Int, String?> {
        var uploaded = 0
        for (relativePath in photoVault.pending()) {
            val slash = relativePath.indexOf('/')
            if (slash <= 0) continue
            val bucket = relativePath.substring(0, slash)
            val objectPath = relativePath.substring(slash + 1)
            if (bucket == "kyc") {
                val customerId = objectPath.substringBefore('/')
                if (customerId.isBlank() || syncStore.hasUnappliedCustomerCreate(customerId)) {
                    continue
                }
            } else {
                // Les autres buckets (ex. handover) seront envoyés lors du flux dédié ou étape suivante.
                continue
            }
            val bytes = photoVault.readBytes(relativePath) ?: continue
            when (val res = agentHttpApi.uploadStoragePhoto(bucket, objectPath, bytes)) {
                is ApiResult.Ok -> {
                    photoVault.delete(relativePath)
                    uploaded++
                }
                is ApiResult.Failure -> return uploaded to res.message
            }
        }
        return uploaded to null
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
            _pendingPhotos.value = photoVault.pending().size
            _syncItems.value = syncStore.listAll()
            _syncMessage.value = "Action corrigée et remise en file d'envoi."
        }
    }

    fun logout() {
        tokens.clear()
        cache.clear()
        _syncMessage.value = ""
        _dashboard.value = DashboardState.Loading
        _loginVm = null
        _customerVm = null
        _kycVm = null
        _paymentVm = null
        _deliveryVm = null
        _applicationVm = null
        _applicationPolicy = null
    }
}
