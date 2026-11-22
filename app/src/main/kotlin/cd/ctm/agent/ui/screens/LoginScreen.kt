package cd.ctm.agent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.theme.BrandTeal
import cd.ctm.agent.ui.theme.BrandTealDeep
import com.ctm.credit.agent.ApiResult
import com.ctm.credit.agent.login.LoginViewModel
import com.ctm.credit.http.HttpBackOfficeApi
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    vm: LoginViewModel,
    backOffice: HttpBackOfficeApi,
    onLoggedIn: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var resetMessage by remember { mutableStateOf<Pair<AlertKind, String>?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val state by vm.state.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        if (state is LoginViewModel.State.Success) onLoggedIn()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        BrandTeal.copy(alpha = 0.16f),
                        MaterialTheme.colorScheme.background,
                    ),
                ),
            )
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        CtmCard(
            modifier = Modifier.widthIn(max = 440.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(BrandTeal, BrandTealDeep))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Smartphone,
                        contentDescription = "Logo CTM",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Se connecter",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "CTM · Crédit Téléphone — Veuillez saisir votre email et mot de passe",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                placeholder = { Text("vous@exemple.com") },
                leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_email_input"),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mot de passe") },
                placeholder = { Text("Votre mot de passe") },
                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showPassword) "Masquer le mot de passe" else "Afficher le mot de passe",
                        )
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("login_password_input"),
            )

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                TextButton(
                    enabled = !resetting,
                    onClick = {
                        val trimmed = email.trim()
                        if (trimmed.isBlank()) {
                            resetMessage = AlertKind.ERROR to "Saisissez d'abord votre e-mail ci-dessus."
                        } else {
                            resetting = true
                            resetMessage = null
                            scope.launch {
                                when (val res = backOffice.resetPassword(trimmed)) {
                                    is ApiResult.Ok -> resetMessage = AlertKind.OK to "Si ce compte existe, un e-mail de réinitialisation vient d'être envoyé."
                                    is ApiResult.Failure -> resetMessage = AlertKind.ERROR to res.message
                                }
                                resetting = false
                            }
                        }
                    },
                    modifier = Modifier.testTag("forgot_password_button"),
                ) {
                    Text("Mot de passe oublié ?")
                }
            }

            if (state is LoginViewModel.State.Error) {
                CtmAlertBanner(AlertKind.ERROR, (state as LoginViewModel.State.Error).message)
            }
            resetMessage?.let { (kind, msg) ->
                CtmAlertBanner(kind, msg)
            }

            Button(
                onClick = {
                    resetMessage = null
                    vm.login(email.trim(), password)
                },
                enabled = state !is LoginViewModel.State.Loading,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("login_submit_button"),
            ) {
                if (state is LoginViewModel.State.Loading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Se connecter", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}
