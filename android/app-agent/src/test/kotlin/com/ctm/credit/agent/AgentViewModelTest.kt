package com.ctm.credit.agent

import com.ctm.credit.agent.application.ApplicationViewModel
import com.ctm.credit.agent.delivery.DeliveryViewModel
import com.ctm.credit.agent.login.LoginViewModel
import com.ctm.credit.agent.payment.PaymentViewModel
import com.ctm.credit.model.Frequency
import com.ctm.credit.network.SyncEngine
import com.ctm.credit.security.FakeTokenStore
import com.ctm.credit.security.PhotoVault
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test fun `login réussi sauvegarde les jetons et passe à Success`() = scope.runTest {
        val tokenStore = FakeTokenStore()
        val vm = LoginViewModel(FakeAgentApi(), tokenStore, backgroundScope)
        vm.login("agent@ctm.cd", "secret")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(tokenStore.isLoggedIn())
        assertTrue(vm.state.value is LoginViewModel.State.Success)
    }

    @Test fun `email vide renvoie une erreur immédiate`() = scope.runTest {
        val vm = LoginViewModel(FakeAgentApi(), FakeTokenStore(), backgroundScope)
        vm.login("", "secret")
        assertTrue(vm.state.value is LoginViewModel.State.Error)
    }

    @Test fun `mot de passe vide renvoie une erreur immédiate`() = scope.runTest {
        val vm = LoginViewModel(FakeAgentApi(), FakeTokenStore(), backgroundScope)
        vm.login("agent@ctm.cd", "")
        assertTrue(vm.state.value is LoginViewModel.State.Error)
    }

    @Test fun `login échoué renvoie Error avec le message`() = scope.runTest {
        val api = FakeAgentApi(loginResult = AgentApi.LoginResult.Failure("Identifiants incorrects."))
        val vm = LoginViewModel(api, FakeTokenStore(), backgroundScope)
        vm.login("agent@ctm.cd", "wrong")
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value
        assertTrue(state is LoginViewModel.State.Error)
        assertEquals("Identifiants incorrects.", (state as LoginViewModel.State.Error).message)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ApplicationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun engine() = SyncEngine(FakeSyncStore(), FakeSyncApi())

    @Test fun `simulation réussie avec données valides`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), backgroundScope)
        vm.simulate(ApplicationViewModel.Form(
            customerId  = "cust-1",
            item        = CatalogItem("item-1", "Samsung", "A15", 150.0, null),
            downPayment = "30",
            durationDays = 30,
            frequency   = Frequency.MONTHLY,
        ))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ApplicationViewModel.State.SimulationReady)
    }

    @Test fun `acompte inférieur au minimum renvoie une erreur`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), backgroundScope)
        // Minimum = 20 % de 150 = 30 USD ; on envoie 10
        vm.simulate(ApplicationViewModel.Form(
            item        = CatalogItem("item-1", "Samsung", "A15", 150.0, null),
            downPayment = "10",
            durationDays = 30,
            frequency   = Frequency.MONTHLY,
        ))
        assertTrue(vm.state.value is ApplicationViewModel.State.Error)
        assertTrue((vm.state.value as ApplicationViewModel.State.Error).message.contains("minimum"))
    }

    @Test fun `durée non autorisée renvoie une erreur`() = scope.runTest {
        val vm = ApplicationViewModel(engine(), testPolicy(), backgroundScope)
        vm.simulate(ApplicationViewModel.Form(
            item        = CatalogItem("item-1", "Samsung", "A15", 150.0, null),
            downPayment = "30",
            durationDays = 45, // non dans la liste [30,60,90,120,180]
            frequency   = Frequency.MONTHLY,
        ))
        assertTrue(vm.state.value is ApplicationViewModel.State.Error)
    }

    @Test fun `submit met l'action en file`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = ApplicationViewModel(SyncEngine(store, FakeSyncApi()), testPolicy(), backgroundScope)
        // Simuler d'abord pour obtenir l'Offer
        val form = ApplicationViewModel.Form(
            customerId  = "cust-1",
            item        = CatalogItem("item-1", "Samsung", "A15", 150.0, null),
            downPayment = "30",
            durationDays = 30,
            frequency   = Frequency.MONTHLY,
        )
        vm.simulate(form)
        dispatcher.scheduler.advanceUntilIdle()
        val simState = vm.state.value as ApplicationViewModel.State.SimulationReady
        vm.submit(form, simState.offer)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ApplicationViewModel.State.Queued)
        assertEquals(1, store.items.size)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DeliveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun engine() = SyncEngine(FakeSyncStore(), FakeSyncApi())

    @Test fun `flux complet de livraison`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = DeliveryViewModel(SyncEngine(store, FakeSyncApi()), PhotoVault.Fake(), backgroundScope)

        vm.startDelivery("app-1")
        dispatcher.scheduler.advanceUntilIdle()
        val started = vm.state.value as DeliveryViewModel.State.Started

        vm.verifyImei(started.deliveryId, "490154203237518")
        dispatcher.scheduler.advanceUntilIdle()
        val verified = vm.state.value as DeliveryViewModel.State.ImeiVerified

        vm.recordProof(verified.deliveryId, "${verified.deliveryId}/proof.jpg", "${verified.deliveryId}/sig.jpg")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is DeliveryViewModel.State.ProofRecorded)

        vm.completeDelivery(verified.deliveryId)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is DeliveryViewModel.State.Completed)

        assertEquals(4, store.items.size)
    }

    @Test fun `IMEI invalide bloque la progression`() = scope.runTest {
        val vm = DeliveryViewModel(engine(), PhotoVault.Fake(), backgroundScope)
        vm.startDelivery("app-1")
        dispatcher.scheduler.advanceUntilIdle()
        val started = vm.state.value as DeliveryViewModel.State.Started
        vm.verifyImei(started.deliveryId, "000000000000000")
        assertTrue(vm.state.value is DeliveryViewModel.State.Error)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private fun engine() = SyncEngine(FakeSyncStore(), FakeSyncApi())

    @Test fun `paiement valide est mis en file`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = PaymentViewModel(SyncEngine(store, FakeSyncApi()), backgroundScope)
        vm.submit(PaymentViewModel.Form("CTM-00001", "25.50", ""))
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.value as PaymentViewModel.State.Queued
        assertEquals("CTM-00001", state.contractNumber)
        assertEquals(25.50, state.amount)
        assertEquals(1, store.items.size)
    }

    @Test fun `montant nul renvoie une erreur`() = scope.runTest {
        val vm = PaymentViewModel(engine(), backgroundScope)
        vm.submit(PaymentViewModel.Form("CTM-00001", "0", ""))
        assertTrue(vm.state.value is PaymentViewModel.State.Error)
    }

    @Test fun `contrat vide renvoie une erreur`() = scope.runTest {
        val vm = PaymentViewModel(engine(), backgroundScope)
        vm.submit(PaymentViewModel.Form("", "25", ""))
        assertTrue(vm.state.value is PaymentViewModel.State.Error)
    }
}
