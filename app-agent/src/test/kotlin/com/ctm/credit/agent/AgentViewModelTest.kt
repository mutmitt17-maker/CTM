package com.ctm.credit.agent

import com.ctm.credit.agent.application.ApplicationViewModel
import com.ctm.credit.agent.customer.CustomerViewModel
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.agent.home.HomeViewModel
import com.ctm.credit.agent.login.LoginViewModel
import com.ctm.credit.agent.payment.PaymentViewModel
import com.ctm.credit.model.Frequency
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import kotlin.test.*

private val SAMSUNG = CatalogItem("item-1", "Samsung", "A15", 150.0, null)
private const val VALID_IMEI = "490154203237518"
private const val BAD_IMEI = "490154203237519" // dernier chiffre faux (Luhn)

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test fun `login réussi sauvegarde les jetons et passe à Success`() = scope.runTest {
        val tokenStore = InMemoryTokenStore()
        val vm = LoginViewModel(FakeAgentApi(), tokenStore, this)
        vm.login("agent@ctm.cd", "secret")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(tokenStore.isLoggedIn())
        assertTrue(vm.state.value is LoginViewModel.State.Success)
    }

    @Test fun `email vide renvoie une erreur immédiate`() = scope.runTest {
        val vm = LoginViewModel(FakeAgentApi(), InMemoryTokenStore(), this)
        vm.login("", "secret")
        assertTrue(vm.state.value is LoginViewModel.State.Error)
    }

    @Test fun `mot de passe vide renvoie une erreur immédiate`() = scope.runTest {
        val vm = LoginViewModel(FakeAgentApi(), InMemoryTokenStore(), this)
        vm.login("agent@ctm.cd", "")
        assertTrue(vm.state.value is LoginViewModel.State.Error)
    }

    @Test fun `login échoué renvoie Error avec le message`() = scope.runTest {
        val api = FakeAgentApi(loginResult = AgentApi.LoginResult.Failure("Identifiants incorrects."))
        val vm = LoginViewModel(api, InMemoryTokenStore(), this)
        vm.login("agent@ctm.cd", "wrong")
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is LoginViewModel.State.Error)
        assertEquals("Identifiants incorrects.", (state as LoginViewModel.State.Error).message)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun engine() = SyncEngine(FakeSyncStore(), FakeSyncApi())

    @Test fun `calculateur conforme au serveur`() = scope.runTest {
        val vm = HomeViewModel(FakeAgentApi(), engine(), this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as HomeViewModel.State.Ready
        assertTrue(ready.calculatorOk)
        assertEquals(2, ready.items.size)
    }

    @Test fun `un seul écart avec le serveur désactive la simulation`() = scope.runTest {
        val bad = AgentApi.OfflineCatalogueResult(listOf(SAMSUNG), testPolicy(), divergentSpec())
        val vm = HomeViewModel(FakeAgentApi(catalogue = bad), engine(), this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as HomeViewModel.State.Ready
        assertFalse(ready.calculatorOk)
    }

    @Test fun `sans réseau le cache est utilisé et la simulation reste désactivée par défaut`() = scope.runTest {
        val cache = listOf(SAMSUNG) to testPolicy()
        val vm = HomeViewModel(FakeAgentApi(catalogueFails = true), engine(), this, cache)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as HomeViewModel.State.Ready
        assertEquals(listOf(SAMSUNG), ready.items)
        assertFalse(ready.calculatorOk)
    }

    @Test fun `sans réseau le cache vérifié garde la simulation active`() = scope.runTest {
        val cache = listOf(SAMSUNG) to testPolicy()
        val vm = HomeViewModel(FakeAgentApi(catalogueFails = true), engine(), this, cache, cachedCalculatorOk = true)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue((vm.state.value as HomeViewModel.State.Ready).calculatorOk)
    }

    @Test fun `sans reseau la session et le catalogue en cache permettent de travailler`() = scope.runTest {
        val cache = listOf(SAMSUNG) to testPolicy()
        val session = AgentSession("uid-1", "ag-1", "Kinshasa", "Jean", "field_agent", "active")
        val api = FakeAgentApi(meFails = true, catalogueFails = true)
        val vm = HomeViewModel(api, engine(), this, cache, cachedCalculatorOk = true, cachedSession = session)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as HomeViewModel.State.Ready
        assertEquals(session, ready.session)
        assertEquals(listOf(SAMSUNG), ready.items)
        assertTrue(ready.calculatorOk)
    }

    @Test fun `sans reseau et sans session en cache renvoie une erreur`() = scope.runTest {
        val vm = HomeViewModel(FakeAgentApi(meFails = true), engine(), this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is HomeViewModel.State.Error)
    }

    @Test fun `sans réseau et sans cache renvoie une erreur`() = scope.runTest {
        val vm = HomeViewModel(FakeAgentApi(catalogueFails = true), engine(), this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is HomeViewModel.State.Error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CustomerViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun form(first: String = "Jean") = CustomerViewModel.Form(
        firstName = first, lastName = "Mukendi", phone = "+243812345678",
        nationalId = "ID-123", address = "Av. de la Paix 12", city = "Lubumbashi",
    )

    @Test fun `client valide - les champs correspondent au serveur`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = CustomerViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(form())
        dispatcher.scheduler.advanceUntilIdle()
        val queued = vm.state.value as CustomerViewModel.State.Queued
        val item = store.items.single()
        assertEquals(SyncAction.CUSTOMER_CREATE, item.action)
        assertEquals(queued.customerId, item.entityKey)
        assertEquals(
            setOf("id", "phone_number", "first_name", "last_name", "national_id_number", "address", "city"),
            keysOf(item.payloadJson),
        )
        assertTrue(item.payloadJson.contains("\"phone_number\":\"+243812345678\""))
    }

    @Test fun `numéro de téléphone invalide - rien n'est mis en file`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = CustomerViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(form().copy(phone = "0812345678"))
        assertTrue(vm.state.value is CustomerViewModel.State.Error)
        assertTrue(store.items.isEmpty())
    }

    @Test fun `guillemets et antislash dans un nom sont échappés`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = CustomerViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(form(first = "Jean \"JJ\" \\ Dupont"))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(store.items.single().payloadJson.contains("\"first_name\":\"Jean \\\"JJ\\\" \\\\ Dupont\""))
    }

    @Test fun `ville vide est envoyée comme null`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = CustomerViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(form().copy(city = ""))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(store.items.single().payloadJson.contains("\"city\":null"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun engine() = SyncEngine(FakeSyncStore(), FakeSyncApi())

    private fun form(item: CatalogItem = SAMSUNG, down: String = "30", days: Int = 30) = ApplicationViewModel.Form(
        customerId = "cust-1", item = item, downPayment = down, durationDays = days, frequency = Frequency.MONTHLY,
    )

    @Test fun `simulation correcte - 150 USD, acompte 30, total 131,97`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), this)
        vm.simulate(form())
        // frais 4,50 ; financé 124,50 ; intérêt 7,47 ; total 131,97
        val ready = vm.state.value as ApplicationViewModel.State.SimulationReady
        assertEquals(0, BigDecimal("131.97").compareTo(ready.offer.totalDue))
    }

    @Test fun `prix avec centimes ne plante pas`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), this)
        vm.simulate(form(item = CatalogItem("item-9", "Infinix", "Hot 40", 149.99, null)))
        assertTrue(vm.state.value is ApplicationViewModel.State.SimulationReady)
    }

    @Test fun `acompte avec 3 décimales est refusé sans exception`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), this)
        vm.simulate(form(down = "30.555"))
        assertTrue(vm.state.value is ApplicationViewModel.State.Error)
    }

    @Test fun `acompte inférieur au minimum renvoie une erreur`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), this)
        vm.simulate(form(down = "10")) // minimum = 20 % de 150 = 30 USD
        val state = vm.state.value
        assertTrue(state is ApplicationViewModel.State.Error)
        assertTrue((state as ApplicationViewModel.State.Error).message.contains("minimum"))
    }

    @Test fun `acompte minimum propre au telephone est respecte`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), this)
        // la politique demande 20 % (30 USD), mais ce telephone demande 40 % de 150 = 60 USD
        vm.simulate(form(item = CatalogItem("item-7", "Itel", "A60", 150.0, null, minDownPaymentPct = 40.0), down = "30"))
        val state = vm.state.value
        assertTrue(state is ApplicationViewModel.State.Error)
        assertTrue((state as ApplicationViewModel.State.Error).message.contains("minimum"))
    }

    @Test fun `durée non autorisée renvoie une erreur`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), this)
        vm.simulate(form(days = 45))
        assertTrue(vm.state.value is ApplicationViewModel.State.Error)
    }

    @Test fun `submit - champs du serveur et total attendu`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = ApplicationViewModel(SyncEngine(store, FakeSyncApi()), testPolicy(), this)
        val f = form()
        vm.simulate(f)
        val ready = vm.state.value as ApplicationViewModel.State.SimulationReady
        vm.submit(f, ready.offer)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value is ApplicationViewModel.State.Queued)
        val item = store.items.single()
        assertEquals(SyncAction.APPLICATION_SUBMIT, item.action)
        assertEquals("cust-1", item.entityKey)
        assertEquals(
            setOf("customer_id", "item_id", "down_payment", "duration_days", "frequency", "expected_total_due"),
            keysOf(item.payloadJson),
        )
        assertTrue(item.payloadJson.contains("\"frequency\":\"monthly\""))
        assertTrue(item.payloadJson.contains("\"expected_total_due\":131.97"))
        assertTrue(item.payloadJson.contains("\"down_payment\":30.00"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test fun `flux complet de livraison par appels directs`() = scope.runTest {
        val api = FakeAgentApi()
        val vm = DeliveryViewModel(api, ConstantFingerprint("fp-agent"), this)

        vm.startDelivery("app-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.Started("deliv-1"), vm.state.value)

        vm.verifyImei("deliv-1", VALID_IMEI)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.ImeiVerified("deliv-1", VALID_IMEI), vm.state.value)

        vm.recordProof("deliv-1", "deliv-1/proof.jpg", "sighash")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.ProofRecorded("deliv-1"), vm.state.value)
        assertEquals(listOf(listOf("deliv-1", "deliv-1/proof.jpg", "sighash", "fp-agent")), api.proofCalls)

        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.EnrollmentRequested("deliv-1"), vm.state.value)
        assertEquals(1, api.enrollCalls)

        vm.checkEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.Enrolled("deliv-1"), vm.state.value)

        vm.completeDelivery("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.Completed("deliv-1"), vm.state.value)
    }

    @Test fun `enrolement en attente puis confirme par le serveur`() = scope.runTest {
        val api = FakeAgentApi().apply {
            mdmStatuses.addLast(ApiResult.Ok(MdmStatus.TOKEN_REQUESTED))
            mdmStatuses.addLast(ApiResult.Ok(MdmStatus.ENROLLED))
        }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()

        vm.checkEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.EnrollmentRequested("deliv-1"), vm.state.value)

        vm.checkEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.Enrolled("deliv-1"), vm.state.value)
    }

    @Test fun `enrolement echoue permet de relancer la demande`() = scope.runTest {
        val api = FakeAgentApi().apply { mdmStatuses.addLast(ApiResult.Ok(MdmStatus.FAILED)) }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()

        vm.checkEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as DeliveryViewModel.State.Error
        assertEquals(DeliveryViewModel.State.ProofRecorded("deliv-1"), state.previousState)

        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(DeliveryViewModel.State.EnrollmentRequested("deliv-1"), vm.state.value)
        assertEquals(2, api.enrollCalls)
    }

    @Test fun `refus du serveur pour la demande d enrolement est affiche`() = scope.runTest {
        val api = FakeAgentApi().apply { enrollResult = ApiResult.Failure("Scannez et vérifiez d'abord l'IMEI.") }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as DeliveryViewModel.State.Error
        assertEquals("Scannez et vérifiez d'abord l'IMEI.", state.message)
        assertEquals(DeliveryViewModel.State.ProofRecorded("deliv-1"), state.previousState)
    }

    @Test fun `cloture refusee reste a l etape enrole`() = scope.runTest {
        val api = FakeAgentApi().apply { completeResult = ApiResult.Failure("Enrôlement non confirmé.") }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.completeDelivery("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as DeliveryViewModel.State.Error
        assertEquals(DeliveryViewModel.State.Enrolled("deliv-1"), state.previousState)
    }

    @Test fun `IMEI invalide - aucun appel au serveur`() = scope.runTest {
        val api = FakeAgentApi()
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.verifyImei("deliv-1", BAD_IMEI)
        val state = vm.state.value as DeliveryViewModel.State.Error
        assertEquals(DeliveryViewModel.State.Started("deliv-1"), state.previousState)
        assertTrue(api.imeiCalls.isEmpty())
    }

    @Test fun `IMEI refusé par le serveur reste à l'étape précédente`() = scope.runTest {
        val api = FakeAgentApi().apply { imeiResult = ApiResult.Ok(ImeiCheck(matched = false, reason = "mismatch")) }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.verifyImei("deliv-1", VALID_IMEI)
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as DeliveryViewModel.State.Error
        assertEquals(DeliveryViewModel.State.Started("deliv-1"), state.previousState)
    }

    @Test fun `erreur du serveur au démarrage est affichée`() = scope.runTest {
        val api = FakeAgentApi().apply { startResult = ApiResult.Failure("Livraison indisponible pour cet agent.") }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.startDelivery("app-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Livraison indisponible pour cet agent.", (vm.state.value as DeliveryViewModel.State.Error).message)
    }

    @Test fun `coupure réseau devient un message d'erreur`() = scope.runTest {
        val api = FakeAgentApi().apply { startThrows = true }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.startDelivery("app-1")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is DeliveryViewModel.State.Error)
    }

    @Test fun `preuve sans photo ou sans signature est refusée`() = scope.runTest {
        val api = FakeAgentApi()
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.recordProof("deliv-1", "", "sighash")
        assertTrue(vm.state.value is DeliveryViewModel.State.Error)
        assertTrue(api.proofCalls.isEmpty())
    }

    @Test fun `preuve avec upload envoie photo JPEG et signature PNG avant record_handover_proof`() = scope.runTest {
        val api = FakeAgentApi()
        val vm = DeliveryViewModel(api, ConstantFingerprint("fp-agent"), this)
        vm.recordProofWithUpload(
            deliveryId = "deliv-1",
            photoObjectPath = "deliv-1/photo.jpg",
            photoBytes = byteArrayOf(1, 2, 3),
            signatureObjectPath = "deliv-1/sig.png",
            signaturePngBytes = byteArrayOf(4, 5, 6, 7),
            signatureHash = "abc123",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeliveryViewModel.State.ProofRecorded("deliv-1"), vm.state.value)
        assertEquals(
            listOf(
                Triple("deliv-1/photo.jpg", "image/jpeg", 3),
                Triple("deliv-1/sig.png", "image/png", 4),
            ),
            api.handoverUploads,
        )
        assertEquals(listOf(listOf("deliv-1", "deliv-1/photo.jpg", "abc123", "fp-agent")), api.proofCalls)
    }

    @Test fun `echec upload photo handover bloque record_handover_proof`() = scope.runTest {
        val api = FakeAgentApi().apply { handoverUploadResult = ApiResult.Failure("Stockage indisponible") }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.recordProofWithUpload(
            deliveryId = "deliv-1",
            photoObjectPath = "deliv-1/photo.jpg",
            photoBytes = byteArrayOf(1, 2),
            signatureObjectPath = "deliv-1/sig.png",
            signaturePngBytes = byteArrayOf(3, 4),
            signatureHash = "abc",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value is DeliveryViewModel.State.Error)
        assertTrue(api.proofCalls.isEmpty())
    }

    @Test fun `polling automatique MDM confirme l enrolement puis s arrete`() = scope.runTest {
        val api = FakeAgentApi().apply {
            mdmStatuses.addLast(ApiResult.Ok(MdmStatus.TOKEN_REQUESTED))
            mdmStatuses.addLast(ApiResult.Ok(MdmStatus.TOKEN_REQUESTED))
            mdmStatuses.addLast(ApiResult.Ok(MdmStatus.ENROLLED))
        }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()

        vm.startEnrollmentPolling("deliv-1", intervalMs = 1_000L, maxAttempts = 5)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeliveryViewModel.State.Enrolled("deliv-1"), vm.state.value)
        assertFalse(vm.mdmPolling.value)
        assertFalse(vm.mdmPollTimedOut.value)
    }

    @Test fun `polling automatique MDM signale le depassement de delai sans bloquer`() = scope.runTest {
        val api = FakeAgentApi().apply {
            repeat(3) { mdmStatuses.addLast(ApiResult.Ok(MdmStatus.TOKEN_REQUESTED)) }
        }
        val vm = DeliveryViewModel(api, ConstantFingerprint(), this)
        vm.requestEnrollment("deliv-1")
        dispatcher.scheduler.advanceUntilIdle()

        vm.startEnrollmentPolling("deliv-1", intervalMs = 500L, maxAttempts = 3)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(DeliveryViewModel.State.EnrollmentRequested("deliv-1"), vm.state.value)
        assertFalse(vm.mdmPolling.value)
        assertTrue(vm.mdmPollTimedOut.value)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun engine() = SyncEngine(FakeSyncStore(), FakeSyncApi())

    @Test fun `paiement valide - numéro de contrat et montant envoyés au serveur`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = PaymentViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(PaymentViewModel.Form(" ct-2026-000123 ", "25,50", ""))
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as PaymentViewModel.State.Queued
        assertEquals("CT-2026-000123", state.contractNumber)
        assertEquals(0, BigDecimal("25.50").compareTo(state.amount))

        val item = store.items.single()
        assertEquals(SyncAction.PAYMENT_CASH, item.action)
        assertEquals(setOf("contract_number", "amount", "note", "collected_at"), keysOf(item.payloadJson))
        assertTrue(item.payloadJson.contains("\"contract_number\":\"CT-2026-000123\""))
        assertTrue(item.payloadJson.contains("\"amount\":25.50"))
    }

    @Test fun `montant nul renvoie une erreur`() = scope.runTest {
        val vm = PaymentViewModel(engine(), this)
        vm.submit(PaymentViewModel.Form("CT-2026-000123", "0", ""))
        assertTrue(vm.state.value is PaymentViewModel.State.Error)
    }

    @Test fun `montant avec 3 décimales est refusé`() = scope.runTest {
        val vm = PaymentViewModel(engine(), this)
        vm.submit(PaymentViewModel.Form("CT-2026-000123", "10.005", ""))
        assertTrue(vm.state.value is PaymentViewModel.State.Error)
    }

    @Test fun `contrat vide renvoie une erreur`() = scope.runTest {
        val vm = PaymentViewModel(engine(), this)
        vm.submit(PaymentViewModel.Form("", "25", ""))
        assertTrue(vm.state.value is PaymentViewModel.State.Error)
    }

    @Test fun `numéro de contrat au mauvais format est refusé`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = PaymentViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(PaymentViewModel.Form("CTM-00001", "25", ""))
        assertTrue(vm.state.value is PaymentViewModel.State.Error)
        assertTrue(store.items.isEmpty())
    }
}

class QueueSupportTest {
    @Test fun `les identifiants locaux sont strictement croissants`() {
        val ids = List(1000) { LocalIds.next() }
        assertEquals(ids.sorted().distinct(), ids)
    }

    @Test fun `parseMoney accepte la virgule et refuse le négatif et les 3 décimales`() {
        assertEquals(0, BigDecimal("12.50").compareTo(parseMoney("12,5")))
        assertNull(parseMoney("-1"))
        assertNull(parseMoney("1.234"))
        assertNull(parseMoney("abc"))
    }

    @Test fun `Json échappe les caractères de contrôle`() {
        assertEquals("\"a\\nb\\u0001\"", Json.quote("a\nb\u0001"))
    }
}
