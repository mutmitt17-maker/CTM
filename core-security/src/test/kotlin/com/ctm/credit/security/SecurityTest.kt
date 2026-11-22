package com.ctm.credit.security

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class TokenStoreTest {
    @Test fun `sauvegarde et restitue les jetons`() {
        val store = FakeTokenStore()
        assertFalse(store.isLoggedIn())
        store.save("acc-1", "ref-1")
        assertTrue(store.isLoggedIn())
        assertEquals("acc-1", store.accessToken())
        assertEquals("ref-1", store.refreshToken())
    }

    @Test fun `clear efface les jetons`() {
        val store = FakeTokenStore()
        store.save("acc", "ref")
        store.clear()
        assertFalse(store.isLoggedIn())
        assertNull(store.accessToken())
        assertNull(store.refreshToken())
    }
}

class DeviceFingerprintTest {
    @Test fun `renvoie une chaîne de 64 caractères`() {
        val fp = FakeDeviceFingerprint()
        assertEquals(64, fp.get().length)
    }

    @Test fun `stable entre deux appels`() {
        val fp = FakeDeviceFingerprint("b".repeat(64))
        assertEquals(fp.get(), fp.get())
    }
}

class BiometricGateTest {
    @Test fun `succès renvoie un token`() = runBlocking {
        val gate = FakeBiometricGate(FakeBiometricGate.Mode.PASS)
        assertTrue(gate.isAvailable())
        val result = gate.authenticate("Test", "")
        assertTrue(result is BiometricGate.Result.Success)
        assertTrue((result as BiometricGate.Result.Success).token.isNotBlank())
    }

    @Test fun `annulation renvoie Cancelled`() = runBlocking {
        val gate = FakeBiometricGate(FakeBiometricGate.Mode.CANCEL)
        val result = gate.authenticate("Test", "")
        assertEquals(BiometricGate.Result.Cancelled, result)
    }

    @Test fun `échec renvoie Failed`() = runBlocking {
        val gate = FakeBiometricGate(FakeBiometricGate.Mode.FAIL)
        val result = gate.authenticate("Test", "")
        assertTrue(result is BiometricGate.Result.Failed)
    }

    @Test fun `non disponible renvoie Unavailable et isAvailable false`() = runBlocking {
        val gate = FakeBiometricGate(FakeBiometricGate.Mode.UNAVAILABLE)
        assertFalse(gate.isAvailable())
        val result = gate.authenticate("Test", "")
        assertEquals(BiometricGate.Result.Unavailable, result)
    }
}

class DatabaseKeyFakeTest {
    @Test fun `renvoie exactement 32 octets`() {
        assertEquals(32, DatabaseKey.Fake.get().size)
    }

    @Test fun `get renvoie une copie (pas la même référence)`() {
        val k1 = DatabaseKey.Fake.get()
        val k2 = DatabaseKey.Fake.get()
        assertFalse(k1 === k2)
        assertContentEquals(k1, k2)
    }

    @Test fun `destroy ne lève pas d'exception`() {
        DatabaseKey.Fake.destroy() // ne doit pas lever
    }
}

class PhotoVaultFakeTest {
    @Test fun `create renvoie un fichier inscriptible`() {
        val vault = PhotoVault.Fake()
        val f = vault.create("cust-1/selfie.jpg")
        f.writeText("fake-bytes")
        assertTrue(f.readText().isNotEmpty())
    }

    @Test fun `pending liste les fichiers non supprimés`() {
        val vault = PhotoVault.Fake()
        vault.create("cust-1/id.jpg").writeText("x")
        vault.create("cust-1/selfie.jpg").writeText("x")
        assertEquals(2, vault.pending().size)
    }

    @Test fun `delete retire le fichier de pending`() {
        val vault = PhotoVault.Fake()
        vault.create("cust-1/selfie.jpg").writeText("x")
        vault.delete("cust-1/selfie.jpg")
        assertEquals(0, vault.pending().size)
    }
}
