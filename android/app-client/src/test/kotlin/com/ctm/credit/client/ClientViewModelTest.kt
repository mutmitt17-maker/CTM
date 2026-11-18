package com.ctm.credit.client

import com.ctm.credit.client.contract.ContractViewModel
import com.ctm.credit.client.login.ClientLoginViewModel
import com.ctm.credit.client.pay.PayInstructionsViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

private const val PHONE = "+243812345678"

@OptIn(ExperimentalCoroutinesApi::class)
class ClientLoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test fun `numéro valide - le code est envoyé`() = scope.runTest {
        val api = FakeClientApi()
        val vm = ClientLoginViewModel(api, InMemoryTokenStore(), this)
        vm.requestCode(" $PHONE ")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ClientLoginViewModel.State.EnterCode(PHONE), vm.state.value)
        assertEquals(listOf(PHONE), api.otpRequests)
    }

    @Test fun `numéro invalide - aucun appel au serveur`() = scope.runTest {
        val api = FakeClientApi()
        val vm = ClientLoginViewModel(api, InMemoryTokenStore(), this)
        vm.requestCode("0812345678")
        assertTrue(vm.state.value is ClientLoginViewModel.State.Error)
        assertTrue(api.otpRequests.isEmpty())
    }

    @Test fun `échec d'envoi du code garde le message du serveur`() = scope.runTest {
        val api = FakeClientApi().apply { otpResult = ClientResult.Failure("Trop de demandes.") }
        val vm = ClientLoginViewModel(api, InMemoryTokenStore(), this)
        vm.requestCode(PHONE)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ClientLoginViewModel.State.Error("Trop de demandes.", null), vm.state.value)
    }

    @Test fun `code correct - jetons sauvegardés et connecté`() = scope.runTest {
        val store = InMemoryTokenStore()
        val vm = ClientLoginViewModel(FakeClientApi(), store, this)
        vm.verifyCode(PHONE, "123456")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ClientLoginViewModel.State.LoggedIn, vm.state.value)
        assertEquals("acc", store.accessToken())
        assertEquals("ref", store.refreshToken())
    }

    @Test fun `mauvais code - on reste sur l'écran du code`() = scope.runTest {
        val api = FakeClientApi().apply { verifyResult = ClientResult.Failure("Code incorrect.") }
        val store = InMemoryTokenStore()
        val vm = ClientLoginViewModel(api, store, this)
        vm.verifyCode(PHONE, "123456")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ClientLoginViewModel.State.Error("Code incorrect.", PHONE), vm.state.value)
        assertNull(store.accessToken())
    }

    @Test fun `code non numérique - aucun appel au serveur`() = scope.runTest {
        val api = FakeClientApi()
        val vm = ClientLoginViewModel(api, InMemoryTokenStore(), this)
        vm.verifyCode(PHONE, "12a456")
        assertTrue(vm.state.value is ClientLoginViewModel.State.Error)
        assertTrue(api.verifyRequests.isEmpty())
    }

    @Test fun `session déjà ouverte - démarre connecté, et la déconnexion efface les jetons`() = scope.runTest {
        val store = InMemoryTokenStore().apply { save("a", "r") }
        val vm = ClientLoginViewModel(FakeClientApi(), store, this)
        assertEquals(ClientLoginViewModel.State.LoggedIn, vm.state.value)
        vm.logout()
        assertEquals(ClientLoginViewModel.State.EnterPhone, vm.state.value)
        assertNull(store.accessToken())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ContractViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    @Test fun `un seul contrat - ouvre directement le détail trié`() = scope.runTest {
        val vm = ContractViewModel(FakeClientApi(), this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        val detail = (vm.state.value as ContractViewModel.State.Detail).detail
        assertEquals(listOf(1, 2, 3), detail.schedule.map { it.number })
        assertEquals(listOf("p-2", "p-1"), detail.payments.map { it.id }) // plus récent d'abord
        assertEquals(2, detail.nextInstallment?.number)
        assertEquals(0, BigDecimal("200.00").compareTo(detail.remainingOnSchedule))
        assertFalse(detail.isLocked)
    }

    @Test fun `plusieurs contrats - la liste est affichée`() = scope.runTest {
        val other = CONTRACT.copy(id = "c-2", contractNumber = "CT-2026-000124")
        val api = FakeClientApi().apply { contracts = ClientResult.Ok(listOf(CONTRACT, other)) }
        val vm = ContractViewModel(api, this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, (vm.state.value as ContractViewModel.State.Contracts).contracts.size)
    }

    @Test fun `aucun contrat`() = scope.runTest {
        val api = FakeClientApi().apply { contracts = ClientResult.Ok(emptyList()) }
        val vm = ContractViewModel(api, this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ContractViewModel.State.NoContract, vm.state.value)
    }

    @Test fun `appareil verrouillé est signalé`() = scope.runTest {
        val api = FakeClientApi().apply { statusResult = ClientResult.Ok(status(deviceState = "locked", overdue = 20)) }
        val vm = ContractViewModel(api, this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue((vm.state.value as ContractViewModel.State.Detail).detail.isLocked)
    }

    @Test fun `erreur serveur sur l'échéancier devient un message`() = scope.runTest {
        val api = FakeClientApi().apply { scheduleResult = ClientResult.Failure("Contrat non visible.") }
        val vm = ContractViewModel(api, this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(ContractViewModel.State.Error("Contrat non visible."), vm.state.value)
    }

    @Test fun `coupure réseau devient un message sans planter`() = scope.runTest {
        val api = FakeClientApi().apply { throwOnContracts = true }
        val vm = ContractViewModel(api, this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.value is ContractViewModel.State.Error)
    }

    @Test fun `refresh recharge le contrat ouvert`() = scope.runTest {
        val api = FakeClientApi()
        val vm = ContractViewModel(api, this)
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        api.paymentsResult = ClientResult.Ok(emptyList())
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue((vm.state.value as ContractViewModel.State.Detail).detail.payments.isEmpty())
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PayInstructionsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private suspend fun detail(api: FakeClientApi): com.ctm.credit.client.contract.ContractDetail {
        val vm = ContractViewModel(api, TestScope(dispatcher))
        vm.load()
        dispatcher.scheduler.advanceUntilIdle()
        return (vm.state.value as ContractViewModel.State.Detail).detail
    }

    @Test fun `échéance du jour non payée plus pénalités - montant suggéré`() = scope.runTest {
        val api = FakeClientApi().apply { statusResult = ClientResult.Ok(status(overdue = 5, penalty = "2.50")) }
        val vm = PayInstructionsViewModel(api, this) { LocalDate.of(2026, 3, 20) }
        vm.prepare(detail(api))
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as PayInstructionsViewModel.State.Ready
        assertEquals("CT-2026-000123", ready.reference)
        assertEquals(0, BigDecimal("100.00").compareTo(ready.dueNow))
        assertEquals(0, BigDecimal("102.50").compareTo(ready.suggestedAmount))
        assertEquals(listOf("mpesa"), ready.merchants.map { it.provider }) // le numéro inactif est masqué
    }

    @Test fun `rien d'échu - on suggère la prochaine échéance`() = scope.runTest {
        val api = FakeClientApi()
        val vm = PayInstructionsViewModel(api, this) { LocalDate.of(2026, 2, 20) }
        vm.prepare(detail(api))
        dispatcher.scheduler.advanceUntilIdle()
        val ready = vm.state.value as PayInstructionsViewModel.State.Ready
        assertEquals(0, BigDecimal.ZERO.compareTo(ready.dueNow))
        assertEquals(0, BigDecimal("100.00").compareTo(ready.suggestedAmount))
    }

    @Test fun `contrat soldé - rien à payer`() = scope.runTest {
        val api = FakeClientApi().apply {
            scheduleResult = ClientResult.Ok(listOf(installment(1, "2026-02-15", "100.00", "100.00", InstallmentStatus.PAID)))
        }
        val vm = PayInstructionsViewModel(api, this)
        vm.prepare(detail(api))
        assertEquals(PayInstructionsViewModel.State.NothingToPay, vm.state.value)
    }

    @Test fun `aucun numéro marchand actif`() = scope.runTest {
        val api = FakeClientApi().apply { merchantsResult = ClientResult.Ok(emptyList()) }
        val vm = PayInstructionsViewModel(api, this)
        vm.prepare(detail(api))
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(PayInstructionsViewModel.State.NoMerchant, vm.state.value)
    }

    @Test fun `montant saisi - virgule acceptée, zéro et 3 décimales refusés`() = scope.runTest {
        val vm = PayInstructionsViewModel(FakeClientApi(), this)
        assertEquals(0, BigDecimal("12.50").compareTo(vm.parseAmount("12,5")))
        assertNull(vm.parseAmount("0"))
        assertNull(vm.parseAmount("1.234"))
        assertNull(vm.parseAmount("abc"))
    }
}
