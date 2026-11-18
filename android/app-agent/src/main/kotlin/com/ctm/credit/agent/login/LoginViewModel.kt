package com.ctm.credit.agent.login

import com.ctm.credit.agent.AgentApi
import com.ctm.credit.security.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel de l'écran de connexion de l'agent.
 *
 * L'écran (Activity/Fragment) observe [state] et appelle [login].
 * Pas de dépendance Android : testable avec FakeAgentApi et FakeTokenStore.
 */
class LoginViewModel(
    private val api: AgentApi,
    private val tokenStore: TokenStore,
    private val scope: CoroutineScope,
) {
    sealed class State {
        object Idle : State()
        object Loading : State()
        /** Connexion réussie : l'UI navigue vers HomeActivity. */
        object Success : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun login(email: String, password: String) {
        if (email.isBlank()) { _state.value = State.Error("L'adresse e-mail est obligatoire."); return }
        if (password.isBlank()) { _state.value = State.Error("Le mot de passe est obligatoire."); return }

        _state.value = State.Loading
        scope.launch {
            when (val result = api.login(email.trim(), password)) {
                is AgentApi.LoginResult.Success -> {
                    tokenStore.save(result.accessToken, result.refreshToken)
                    _state.value = State.Success
                }
                is AgentApi.LoginResult.Failure -> {
                    _state.value = State.Error(result.message)
                }
            }
        }
    }

    fun reset() { _state.value = State.Idle }
}
