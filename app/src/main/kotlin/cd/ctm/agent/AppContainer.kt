package cd.ctm.agent

import android.content.Context
import cd.ctm.agent.ai.CtmAiAssistant
import cd.ctm.agent.data.AndroidDatabaseKey
import cd.ctm.agent.data.AndroidDeviceFingerprint
import cd.ctm.agent.data.AndroidPhotoVault
import cd.ctm.agent.data.AndroidTokenStore
import cd.ctm.agent.data.ApplicationStatusTracker
import cd.ctm.agent.data.CatalogueCache
import cd.ctm.agent.data.DeliveryStateStore
import cd.ctm.agent.data.SqliteSyncStore
import cd.ctm.agent.sync.SyncWorker
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
import com.ctm.credit.http.AgentApplicationSummary
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Conteneur d'injection de dépendances de l'application Agent CTM.
 * Conservé au niveau [android.app.Application] afin de préserver les ViewModels
 * et l'état des flux lors des rotations d'écran.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val databaseKey = AndroidDatabaseKey(appContext)
    val tokenStore = AndroidTokenStore(appContext)
    val tokens: TokenStore = tokenStore
    val fingerprint = AndroidDeviceFingerprint(appContext)
    val photoVault = AndroidPhotoVault(appContext)
    private val http = SupabaseHttp(HttpConfig(AppConfig.SUPABASE_URL, AppConfig.SUPABASE_ANON_KEY), tokens, fingerprint)
    val agentHttpApi = HttpAgentApi(http)
    val api: AgentApi = agentHttpApi
    val syncStore = SqliteSyncStore(appContext, databaseKey) { tokenStore.currentAgentId() }
    val syncEngine = SyncEngine(syncStore, HttpSyncApi(http), canSend = ::canSendSyncItem)
    val cache = CatalogueCache(appContext)
    val deliveryStateStore = DeliveryStateStore(appContext)
    val statusTracker = ApplicationStatusTracker(appContext)
    val aiAssistant = CtmAiAssistant()
    private val syncMutex = Mutex()

    data class SyncCycleReport(
        val outcome: SyncOutcome,
        val applied: Int,
        val conflicts: Int,
        val failed: Int,
        val uploadedPhotos: Int,
        val photoError: String? = null,
        val fatalError: String? = null,
    )

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

    private val _otherAgentPending = MutableStateFlow(0)
    val otherAgentPending: StateFlow<Int> = _otherAgentPending

    private val _syncItems = MutableStateFlow<List<SyncItem>>(emptyList())
    val syncItems: StateFlow<List<SyncItem>> = _syncItems

    private val _syncMessage = MutableStateFlow("")
    val syncMessage: StateFlow<String> = _syncMessage

    private val _syncRunning = MutableStateFlow(false)
    val syncRunning: StateFlow<Boolean> = _syncRunning

    private val _lastSyncAtMs = MutableStateFlow(0L)
    val lastSyncAtMs: StateFlow<Long> = _lastSyncAtMs

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

    fun deliveryViewModel(): DeliveryViewModel {
        val current = _deliveryVm
        if (current != null) return current
        return DeliveryViewModel(api, fingerprint, appScope).also { vm ->
            deliveryStateStore.load()?.let { saved -> vm.restore(saved) }
            appScope.launch {
                vm.state.collect { st -> deliveryStateStore.save(st) }
            }
            _deliveryVm = vm
        }
    }

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
                    api.me().also { s ->
                        if (s.userId.isNotBlank()) tokenStore.setCurrentAgentId(s.userId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    snapshot?.session?.also { s ->
                        if (s.userId.isNotBlank()) tokenStore.setCurrentAgentId(s.userId)
                    } ?: throw e
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

                updateQueueSnapshot()
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
            updateQueueSnapshot()
            if (_pending.value > 0 || _pendingPhotos.value > 0) {
                SyncWorker.enqueueImmediate(appContext)
            }
        }
    }

    private fun updateQueueSnapshot() {
        _pending.value = syncEngine.pendingCount()
        _pendingPhotos.value = photoVault.pending().size
        _otherAgentPending.value = syncStore.pendingForOtherAgentCount(tokenStore.currentAgentId())
        _syncItems.value = syncStore.listAll()
    }

    fun syncNow(onNeedsLogin: () -> Unit) {
        if (_syncRunning.value) return
        appScope.launch {
            _syncRunning.value = true
            _syncMessage.value = "Synchronisation en cours…"
            try {
                val report = runSyncCycle()
                _syncMessage.value = when {
                    report.fatalError != null -> "Synchronisation impossible : ${report.fatalError}."
                    report.outcome == SyncOutcome.NEEDS_LOGIN -> "Session expirée. Reconnectez-vous."
                    report.outcome == SyncOutcome.RETRY_LATER -> "Pas de connexion au serveur. Réessayez plus tard."
                    report.outcome == SyncOutcome.REFUSED -> "Le serveur a refusé l'envoi. Contactez l'administrateur."
                    report.outcome == SyncOutcome.INCOMPLETE -> "Envoi interrompu par précaution. Réessayez."
                    report.photoError != null ->
                        "Actions envoyées : ${report.applied}, photos envoyées : ${report.uploadedPhotos}. Échec photo : ${report.photoError}"
                    else -> {
                        val photoSuffix = if (report.uploadedPhotos > 0) ", ${report.uploadedPhotos} photo(s) envoyée(s)" else ""
                        "Terminé : ${report.applied} envoyée(s)$photoSuffix, ${report.conflicts} en conflit, ${report.failed} refusée(s)."
                    }
                }
                if (report.outcome == SyncOutcome.NEEDS_LOGIN) onNeedsLogin()
            } finally {
                _syncRunning.value = false
            }
        }
    }

    /**
     * Exécute un cycle complet (actions -> photos Storage éligibles -> actions suivantes),
     * protégé par mutex afin que WorkManager et l'UI ne se concurrencent jamais.
     */
    suspend fun runSyncCycle(): SyncCycleReport = syncMutex.withLock {
        var totalApplied = 0
        var totalConflicts = 0
        var totalFailed = 0
        var uploadedPhotos = 0
        var photoError: String? = null
        var finalOutcome = SyncOutcome.DONE

        while (true) {
            val report = try {
                syncEngine.runOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateQueueSnapshot()
                return@withLock SyncCycleReport(
                    outcome = SyncOutcome.RETRY_LATER,
                    applied = totalApplied,
                    conflicts = totalConflicts,
                    failed = totalFailed,
                    uploadedPhotos = uploadedPhotos,
                    fatalError = e.message ?: "erreur inconnue",
                )
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

        updateQueueSnapshot()
        _lastSyncAtMs.value = System.currentTimeMillis()
        SyncCycleReport(
            outcome = finalOutcome,
            applied = totalApplied,
            conflicts = totalConflicts,
            failed = totalFailed,
            uploadedPhotos = uploadedPhotos,
            photoError = photoError,
        )
    }

    /**
     * Vérifie les changements de statut des dossiers de l'agent (décisions `worker-biometrics` / révision superviseur).
     */
    suspend fun checkApplicationStatusTransitions(): List<AgentApplicationSummary> {
        val agentId = tokenStore.currentAgentId().orEmpty()
        if (agentId.isBlank() || !tokens.isLoggedIn()) return emptyList()
        return when (val res = agentHttpApi.fetchAgentApplications(limit = 30)) {
            is ApiResult.Ok -> statusTracker.detectTransitions(agentId, res.value)
            is ApiResult.Failure -> emptyList()
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
            updateQueueSnapshot()
            _syncMessage.value = "Action corrigée et remise en file d'envoi."
            SyncWorker.enqueueImmediate(appContext)
        }
    }

    fun logout() {
        tokens.clear()
        cache.clear()
        deliveryStateStore.clear()
        _syncMessage.value = ""
        _dashboard.value = DashboardState.Loading
        _loginVm = null
        _customerVm = null
        _kycVm = null
        _paymentVm = null
        _deliveryVm?.stopPolling()
        _deliveryVm = null
        _applicationVm = null
        _applicationPolicy = null
    }
}
