package com.ctm.credit.agent

import com.ctm.credit.agent.kyc.KycViewModel
import com.ctm.credit.network.SyncAction
import com.ctm.credit.network.SyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class KycViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)

    private fun validForm() = KycViewModel.Form(
        customerId = "cust-123",
        birthDate = "1996-05-14",
        commune = "Gombe",
        neighborhood = "Centre",
        occupation = "Commerçant",
        incomeSource = "Boutique",
        declaredMonthlyIncomeUsd = "450,50",
        idDocumentType = KycViewModel.IdDocumentType.VOTER_CARD,
        idDocumentPhotoPath = "cust-123/id-1.jpg",
        selfiePath = "cust-123/selfie-1.jpg",
        emergencyFullName = "Marie Kabila",
        emergencyPhone = "+243812345678",
        emergencyRelationship = KycViewModel.Relationship.SPOUSE,
        emergencyIsGuarantor = true,
        consentBiometrics = true,
        consentCreditTerms = true,
        consentDeviceLock = true,
    )

    @Test fun `kyc valide met en file kyc contact et consentements sans scores client`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = KycViewModel(SyncEngine(store, FakeSyncApi()), this)
        vm.submit(validForm(), today = LocalDate.of(2026, 11, 22))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(KycViewModel.State.Queued("cust-123"), vm.state.value)
        assertEquals(5, store.items.size)
        assertTrue(store.items.all { it.entityKey == "cust-123" })
        assertEquals(
            listOf(
                SyncAction.KYC_SUBMIT,
                SyncAction.EMERGENCY_CONTACT_ADD,
                SyncAction.CONSENT_RECORD,
                SyncAction.CONSENT_RECORD,
                SyncAction.CONSENT_RECORD,
            ),
            store.items.map { it.action },
        )

        val kycPayload = store.items[0].payloadJson
        assertTrue("\"declared_monthly_income_usd\":450.50" in kycPayload)
        assertTrue("\"id_document_type\":\"voter_card\"" in kycPayload)
        assertTrue("\"id_document_photo_path\":\"cust-123/id-1.jpg\"" in kycPayload)
        assertTrue("\"selfie_path\":\"cust-123/selfie-1.jpg\"" in kycPayload)
        assertFalse("face_match_score" in kycPayload)
        assertFalse("liveness_passed" in kycPayload)
        assertFalse("ocr_data" in kycPayload)
        assertFalse("ocr_confidence" in kycPayload)

        val contactPayload = store.items[1].payloadJson
        assertTrue("\"full_name\":\"Marie Kabila\"" in contactPayload)
        assertTrue("\"phone_number\":\"+243812345678\"" in contactPayload)
        assertTrue("\"relationship\":\"spouse\"" in contactPayload)
        assertTrue("\"is_guarantor\":true" in contactPayload)

        val consentPayloads = store.items.drop(2).map { it.payloadJson }
        assertTrue(consentPayloads[0].contains("\"consent_type\":\"kyc_biometrics\""))
        assertTrue(consentPayloads[1].contains("\"consent_type\":\"credit_terms\""))
        assertTrue(consentPayloads[2].contains("\"consent_type\":\"device_lock_mdm\""))
        assertTrue(consentPayloads.all { "\"granted\":true" in it && "\"text_version\":\"v1.0\"" in it })
    }

    @Test fun `date de naissance invalide ou future est refusee`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = KycViewModel(SyncEngine(store, FakeSyncApi()), this)

        vm.submit(validForm().copy(birthDate = "14/05/1996"))
        assertTrue(vm.state.value is KycViewModel.State.Error)
        assertTrue(store.items.isEmpty())

        vm.submit(validForm().copy(birthDate = "2030-01-01"), today = LocalDate.of(2026, 11, 22))
        assertTrue(vm.state.value is KycViewModel.State.Error)
        assertTrue(store.items.isEmpty())
    }

    @Test fun `photos manquantes ou consentement non coche bloquent la mise en file`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = KycViewModel(SyncEngine(store, FakeSyncApi()), this)

        vm.submit(validForm().copy(idDocumentPhotoPath = ""))
        assertTrue(vm.state.value is KycViewModel.State.Error)

        vm.submit(validForm().copy(selfiePath = ""))
        assertTrue(vm.state.value is KycViewModel.State.Error)

        vm.submit(validForm().copy(consentDeviceLock = false))
        assertTrue(vm.state.value is KycViewModel.State.Error)

        assertTrue(store.items.isEmpty())
    }

    @Test fun `telephone contact urgence invalide ou revenu a 3 decimales refuses`() = scope.runTest {
        val store = FakeSyncStore()
        val vm = KycViewModel(SyncEngine(store, FakeSyncApi()), this)

        vm.submit(validForm().copy(emergencyPhone = "0712345678"))
        assertTrue(vm.state.value is KycViewModel.State.Error)

        vm.submit(validForm().copy(declaredMonthlyIncomeUsd = "100.123"))
        assertTrue(vm.state.value is KycViewModel.State.Error)

        assertTrue(store.items.isEmpty())
    }
}
