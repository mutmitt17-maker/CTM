package cd.ctm.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.navigation.Route
import cd.ctm.agent.ui.screens.AgentPortfolioScreen
import cd.ctm.agent.ui.screens.CustomerScreen
import cd.ctm.agent.ui.screens.DeliveryScreen
import cd.ctm.agent.ui.screens.HomeScreen
import cd.ctm.agent.ui.screens.IntegrationsScreen
import cd.ctm.agent.ui.screens.KycScreen
import cd.ctm.agent.ui.screens.LoginScreen
import cd.ctm.agent.ui.screens.PaymentScreen
import cd.ctm.agent.ui.screens.SimulatorScreen
import cd.ctm.agent.ui.screens.SyncQueueScreen

private data class TopNavItem(
    val labelRes: Int,
    val icon: ImageVector,
    val route: Route,
    val badgeCount: Int = 0,
)

@Composable
fun CtmNavApp(container: AppContainer) {
    val navController = rememberNavController()
    var sessionId by rememberSaveable { mutableIntStateOf(0) }

    val loginVm = container.loginViewModel()
    val customerVm = container.customerViewModel()
    val kycVm = container.kycViewModel()
    val paymentVm = container.paymentViewModel()
    val deliveryVm = container.deliveryViewModel()

    val dash by container.dashboard.collectAsState()
    val pending by container.pending.collectAsState()
    val readyDash = dash as? AppContainer.DashboardState.Ready

    val startDestination: Route = remember {
        if (container.tokens.isLoggedIn()) Route.Home else Route.Login
    }

    LaunchedEffect(sessionId) {
        if (container.tokens.isLoggedIn() && dash is AppContainer.DashboardState.Loading) {
            container.loadDashboard()
        }
    }

    val goLogin: () -> Unit = {
        container.logout()
        sessionId++
        navController.navigate(Route.Login) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val isLoginScreen = currentDest?.hasRoute<Route.Login>() == true

    val navItems = remember(pending) {
        listOf(
            TopNavItem(R.string.nav_home, Icons.Outlined.Home, Route.Home),
            TopNavItem(R.string.nav_customer, Icons.Outlined.PersonAdd, Route.NewCustomer),
            TopNavItem(R.string.nav_delivery, Icons.Outlined.LocalShipping, Route.Delivery),
            TopNavItem(R.string.nav_payment, Icons.Outlined.AccountBalanceWallet, Route.Payment(null)),
            TopNavItem(R.string.nav_sync, Icons.Outlined.Sync, Route.SyncQueue, badgeCount = pending),
        )
    }

    val showChrome = !isLoginScreen && currentDest != null && navItems.any { item ->
        isRouteActive(currentDest, item.route)
    }

    val navigateTab: (Route) -> Unit = { target ->
        navController.navigate(target) {
            popUpTo(Route.Home) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showChrome && !isWide) {
                    NavigationBar(
                        windowInsets = WindowInsets.navigationBars,
                    ) {
                        navItems.forEach { item ->
                            val selected = isRouteActive(currentDest, item.route)
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(item.route) },
                                icon = {
                                    if (item.badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(item.badgeCount.toString()) } }) {
                                            Icon(item.icon, contentDescription = label)
                                        }
                                    } else {
                                        Icon(item.icon, contentDescription = label)
                                    }
                                },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding),
            ) {
                if (showChrome && isWide) {
                    NavigationRail(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        navItems.forEach { item ->
                            val selected = isRouteActive(currentDest, item.route)
                            val label = stringResource(item.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(item.route) },
                                icon = {
                                    if (item.badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(item.badgeCount.toString()) } }) {
                                            Icon(item.icon, contentDescription = label)
                                        }
                                    } else {
                                        Icon(item.icon, contentDescription = label)
                                    }
                                },
                                label = { Text(label, maxLines = 1) },
                            )
                        }
                    }
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                    ) {
                        composable<Route.Login> {
                            LoginScreen(
                                vm = loginVm,
                                onResetPassword = { email -> container.agentHttpApi.resetPassword(email) },
                                onLoggedIn = {
                                    container.loadDashboard()
                                    navController.navigate(Route.Home) {
                                        popUpTo(Route.Login) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                        composable<Route.Home> {
                            HomeScreen(
                                container = container,
                                onNavigate = { route -> navController.navigate(route) },
                                onLogout = goLogin,
                                onNeedsLogin = goLogin,
                            )
                        }

                        composable<Route.Simulator> { entry ->
                            val args = entry.toRoute<Route.Simulator>()
                            val policy = readyDash?.policy
                            if (policy == null) {
                                CtmScreenContainer(
                                    title = stringResource(R.string.simulator_title),
                                    onBack = { navController.popBackStack() },
                                ) {
                                    CtmAlertBanner(
                                        AlertKind.WARN,
                                        stringResource(R.string.simulator_unavailable),
                                    )
                                }
                            } else {
                                val appVm = container.applicationViewModel(policy)
                                SimulatorScreen(
                                    items = readyDash.items,
                                    policy = policy,
                                    vm = appVm,
                                    customerId = args.customerId,
                                    onQueued = { container.refreshQueueState() },
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.NewCustomer> {
                            CustomerScreen(
                                vm = customerVm,
                                aiAssistant = container.aiAssistant,
                                canMakeApplication = readyDash != null && readyDash.calculatorOk && readyDash.items.isNotEmpty(),
                                onKyc = { customerId ->
                                    kycVm.reset()
                                    navController.navigate(Route.Kyc(customerId))
                                },
                                onApplication = { customerId ->
                                    navController.navigate(Route.Simulator(customerId))
                                },
                                onQueued = { container.refreshQueueState() },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<Route.Kyc> { entry ->
                            val args = entry.toRoute<Route.Kyc>()
                            KycScreen(
                                container = container,
                                vm = kycVm,
                                initialCustomerId = args.customerId,
                                canMakeApplication = readyDash != null && readyDash.calculatorOk && readyDash.items.isNotEmpty(),
                                onApplication = { customerId ->
                                    navController.navigate(Route.Simulator(customerId))
                                },
                                onSyncNow = {
                                    container.syncNow(onNeedsLogin = goLogin)
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<Route.Portfolio> {
                            AgentPortfolioScreen(
                                container = container,
                                canMakeApplication = readyDash != null && readyDash.calculatorOk && readyDash.items.isNotEmpty(),
                                onKyc = { customerId ->
                                    kycVm.reset()
                                    navController.navigate(Route.Kyc(customerId))
                                },
                                onApplication = { customerId ->
                                    navController.navigate(Route.Simulator(customerId))
                                },
                                onDeliver = { navController.navigate(Route.Delivery) },
                                onCollectPayment = { contractNumber ->
                                    paymentVm.reset()
                                    navController.navigate(Route.Payment(contractNumber))
                                },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<Route.Delivery> {
                            DeliveryScreen(
                                container = container,
                                vm = deliveryVm,
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<Route.Payment> { entry ->
                            val args = entry.toRoute<Route.Payment>()
                            PaymentScreen(
                                container = container,
                                vm = paymentVm,
                                initialContractNumber = args.contractNumber,
                                onQueued = { container.refreshQueueState() },
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<Route.SyncQueue> {
                            SyncQueueScreen(
                                container = container,
                                onNeedsLogin = goLogin,
                                onBack = { navController.popBackStack() },
                            )
                        }

                        composable<Route.Integrations> {
                            IntegrationsScreen(
                                container = container,
                                onBack = { navController.popBackStack() },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun isRouteActive(dest: androidx.navigation.NavDestination?, route: Route): Boolean {
    if (dest == null) return false
    return when (route) {
        is Route.Home -> dest.hasRoute<Route.Home>()
        is Route.NewCustomer -> dest.hasRoute<Route.NewCustomer>()
        is Route.Delivery -> dest.hasRoute<Route.Delivery>()
        is Route.Payment -> dest.hasRoute<Route.Payment>()
        is Route.SyncQueue -> dest.hasRoute<Route.SyncQueue>()
        else -> false
    }
}
