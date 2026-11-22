package com.ctm.credit.security

/** TokenStore en mémoire pour les tests. */
class FakeTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null
    override fun accessToken() = access
    override fun refreshToken() = refresh
    override fun save(accessToken: String, refreshToken: String) {
        access = accessToken; refresh = refreshToken
    }
    override fun clear() { access = null; refresh = null }
}

/** DeviceFingerprint fixe pour les tests. */
class FakeDeviceFingerprint(private val value: String = "a".repeat(64)) : DeviceFingerprint {
    override fun get() = value
}

/**
 * BiometricGate configurable pour les tests.
 * [mode] peut être PASS, CANCEL ou FAIL.
 */
class FakeBiometricGate(var mode: Mode = Mode.PASS) : BiometricGate {
    enum class Mode { PASS, CANCEL, FAIL, UNAVAILABLE }

    override fun isAvailable() = mode != Mode.UNAVAILABLE

    override suspend fun authenticate(title: String, subtitle: String): BiometricGate.Result =
        when (mode) {
            Mode.PASS       -> BiometricGate.Result.Success("fake-bio-token")
            Mode.CANCEL     -> BiometricGate.Result.Cancelled
            Mode.FAIL       -> BiometricGate.Result.Failed()
            Mode.UNAVAILABLE -> BiometricGate.Result.Unavailable
        }
}
