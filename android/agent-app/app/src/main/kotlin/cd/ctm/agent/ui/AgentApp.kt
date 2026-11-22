package cd.ctm.agent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cd.ctm.agent.AppContainer
import com.ctm.credit.agent.home.HomeViewModel

/** Écrans de l'application. Pas de bibliothèque de navigation : l'état courant suffit pour 5 écrans. */
sealed interface Route {
    data object Login : Route
    data object Home : Route
    /** [customerId] non nul : l'agent peut envoyer le dossier après la simulation. */
    data class Simulator(val customerId: String?) : Route
    data object NewCustomer : Route
    data object Payment : Route
}

@Composable
fun AgentApp(container: AppContainer) {
    var route by remember { mutableStateOf<Route>(if (container.tokens.isLoggedIn()) Route.Home else Route.Login) }
    // Change à chaque déconnexion : les ViewModels sont recréés, aucune donnée de l'agent précédent ne reste affichée.
    var sessionId by remember { mutableIntStateOf(0) }

    val loginVm = remember(sessionId) { container.newLoginViewModel() }
    val homeVm = remember(sessionId) { container.newHomeViewModel() }
    val customerVm = remember(sessionId) { container.newCustomerViewModel() }
    val paymentVm = remember(sessionId) { container.newPaymentViewModel() }

    val goLogin = {
        container.logout()
        sessionId++
        route = Route.Login
    }

    // Au démarrage avec une session déjà ouverte : charge le catalogue.
    LaunchedEffect(sessionId) {
        if (container.tokens.isLoggedIn()) homeVm.load()
    }
    LaunchedEffect(route) { container.refreshPending() }

    BackHandler(enabled = route != Route.Login && route != Route.Home) { route = Route.Home }

    val homeState by homeVm.state.collectAsState()
    val ready = homeState as? HomeViewModel.State.Ready

    when (val r = route) {
        is Route.Login -> LoginScreen(loginVm, onLoggedIn = {
            route = Route.Home
            homeVm.load()
        })
        is Route.Home -> HomeScreen(
            container = container,
            home = homeVm,
            onSimulate = { route = Route.Simulator(null) },
            onNewCustomer = { route = Route.NewCustomer },
            onPayment = { route = Route.Payment },
            onLogout = goLogin,
            onNeedsLogin = goLogin,
        )
        is Route.Simulator -> {
            if (ready == null) {
                // Le catalogue n'est pas chargé : retour à l'accueil (hors composition, pas d'écriture d'état ici).
                LaunchedEffect(Unit) { route = Route.Home }
            } else {
                val appVm = remember(ready.policy) { container.newApplicationViewModel(ready.policy) }
                SimulatorScreen(ready.items, ready.policy, appVm, r.customerId, onBack = { route = Route.Home })
            }
        }
        is Route.NewCustomer -> CustomerScreen(
            vm = customerVm,
            canMakeApplication = ready != null && ready.calculatorOk,
            onApplication = { customerId -> route = Route.Simulator(customerId) },
            onBack = { route = Route.Home },
        )
        is Route.Payment -> PaymentScreen(paymentVm, onBack = { route = Route.Home })
    }
}
