package cd.ctm.agent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cd.ctm.agent.AppContainer
import cd.ctm.agent.data.CatalogueCache
import com.ctm.credit.agent.home.HomeViewModel

@Composable
fun HomeScreen(
    container: AppContainer,
    home: HomeViewModel,
    onSimulate: () -> Unit,
    onNewCustomer: () -> Unit,
    onPayment: () -> Unit,
    onLogout: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val state by home.state.collectAsState()
    val pending by container.pending.collectAsState()
    val syncMessage by container.syncMessage.collectAsState()

    // On garde le dernier catalogue reçu pour pouvoir travailler sans réseau au prochain démarrage.
    LaunchedEffect(state) {
        val s = state
        if (s is HomeViewModel.State.Ready) {
            container.cache.save(CatalogueCache.Snapshot(s.session, s.items, s.policy, s.calculatorOk))
        }
    }

    ScreenFrame("Accueil") {
        when (val s = state) {
            is HomeViewModel.State.Loading -> {
                CircularProgressIndicator()
                Text("Chargement du catalogue…")
            }
            is HomeViewModel.State.Error -> {
                ErrorText(s.message)
                Button(onClick = { home.load() }) { Text("Réessayer") }
            }
            is HomeViewModel.State.Ready -> {
                Text("Agence : ${s.session.agencyName.ifBlank { "—" }}")
                Text("Compte : ${s.session.agentName.ifBlank { "—" }} (${s.session.role})")
                Text("${s.items.size} téléphone(s) en stock dans votre agence.")
                if (!s.calculatorOk) {
                    ErrorText(
                        "Simulation hors ligne désactivée : le calculateur du téléphone ne correspond pas à celui du serveur. " +
                            "Contactez l'administrateur.",
                    )
                }
                Button(
                    onClick = onSimulate,
                    enabled = s.calculatorOk && s.items.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Simuler un crédit") }
                Button(onClick = onNewCustomer, modifier = Modifier.fillMaxWidth()) { Text("Nouveau client") }
                Button(onClick = onPayment, modifier = Modifier.fillMaxWidth()) { Text("Encaisser un paiement") }
            }
        }

        SectionTitle("Synchronisation")
        Text("$pending action(s) en attente d'envoi.")
        if (syncMessage.isNotBlank()) Text(syncMessage)
        OutlinedButton(onClick = { container.syncNow(onNeedsLogin) }, modifier = Modifier.fillMaxWidth()) {
            Text("Synchroniser maintenant")
        }
        OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) { Text("Se déconnecter") }
    }
}
