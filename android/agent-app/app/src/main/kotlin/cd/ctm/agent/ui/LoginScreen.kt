package cd.ctm.agent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.ctm.credit.agent.login.LoginViewModel

@Composable
fun LoginScreen(vm: LoginViewModel, onLoggedIn: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val state by vm.state.collectAsState()

    LaunchedEffect(state) {
        if (state is LoginViewModel.State.Success) onLoggedIn()
    }

    ScreenFrame("CTM Agent") {
        Text("Connectez-vous avec votre compte agent.")
        Field("Email", email, { email = it }, keyboard = KeyboardType.Email)
        Field("Mot de passe", password, { password = it }, keyboard = KeyboardType.Password, password = true)
        val current = state
        if (current is LoginViewModel.State.Error) ErrorText(current.message)
        if (current is LoginViewModel.State.Loading) {
            CircularProgressIndicator()
        } else {
            Button(onClick = { vm.login(email.trim(), password) }, modifier = Modifier.fillMaxWidth()) {
                Text("Se connecter")
            }
        }
    }
}
