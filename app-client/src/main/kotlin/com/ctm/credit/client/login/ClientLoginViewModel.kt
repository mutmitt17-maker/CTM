package com.ctm.credit.client.login

import com.ctm.credit.client.ClientApi
import com.ctm.credit.client.fold
import com.ctm.credit.model.CongoPhone
import com.ctm.credit.security.TokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Connexion du client par code à usage unique (OTP) envoyé par SMS.
 * Étapes : [requestCode] (numéro de téléphone) puis [verifyCode] (code reçu).
 * Le serveur bloque le code après 5 essais ; ce ViewModel affiche simplement le message renvoyé.
 */
class ClientLoginViewModel(
    private val api: ClientApi,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
) {
    sealed class State {
        /** Écran du numéro de téléphone. */
        object EnterPhone : State()
        object Working : State()
        /** Écran du code : le code a été envoyé à [phone]. */
        data class EnterCode(val phone: String) : State()
        object LoggedIn : State()
        /** [phone] non nul : l'erreur concerne l'étape du code (on reste sur cet écran). */
        data class Error(val message: String, val phone: String?) : State()
    }

    private val _state = MutableStateFlow<State>(if (tokenStore.isLoggedIn()) State.LoggedIn else State.EnterPhone)
    val state: StateFlow<State> = _state

    fun requestCode(phoneInput: String) {
        val phone = phoneInput.trim()
        if (!CongoPhone.isValid(phone)) {
            _state.value = State.Error("Numéro invalide (format : +243XXXXXXXXX).", null)
            return
        }
        _state.value = State.Working
        scope.launch {
            try {
                api.requestOtp(phone).fold(
                    onOk = { _state.value = State.EnterCode(phone) },
                    onFailure = { _state.value = State.Error(it, null) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Erreur réseau. Réessayez.", null)
            }
        }
    }

    fun verifyCode(phone: String, codeInput: String) {
        val code = codeInput.trim()
        if (!CODE.matches(code)) {
            _state.value = State.Error("Le code doit contenir uniquement des chiffres (4 à 8).", phone)
            return
        }
        _state.value = State.Working
        scope.launch {
            try {
                api.verifyOtp(phone, code).fold(
                    onOk = { tokens ->
                        tokenStore.save(tokens.accessToken, tokens.refreshToken)
                        _state.value = State.LoggedIn
                    },
                    onFailure = { _state.value = State.Error(it, phone) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "Erreur réseau. Réessayez.", phone)
            }
        }
    }

    fun logout() {
        tokenStore.clear()
        _state.value = State.EnterPhone
    }

    private companion object {
        val CODE = Regex("""\d{4,8}""")
    }
}
