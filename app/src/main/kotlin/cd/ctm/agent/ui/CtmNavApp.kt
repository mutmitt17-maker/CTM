package cd.ctm.agent.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.outlined.PhoneAndroid
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import cd.ctm.agent.AppContainer
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.navigation.Route
import cd.ctm.agent.ui.screens.ApplicationsScreen
import cd.ctm.agent.ui.screens.ApprovalsScreen
import cd.ctm.agent.ui.screens.CashConfirmScreen
import cd.ctm.agent.ui.screens.ClientPortalScreen
import cd.ctm.agent.ui.screens.CollectionsScreen
import cd.ctm.agent.ui.screens.ContractsReliefScreen
import cd.ctm.agent.ui.screens.CreditPolicyScreen
import cd.ctm.agent.ui.screens.CustomerScreen
import cd.ctm.agent.ui.screens.DeliveryScreen
import cd.ctm.agent.ui.screens.DevicePolicyScreen
import cd.ctm.agent.ui.screens.DevicesScreen
import cd.ctm.agent.ui.screens.HomeScreen
import cd.ctm.agent.ui.screens.LoginScreen
import cd.ctm.agent.ui.screens.ManualMobileMoneyScreen
import cd.ctm.agent.ui.screens.MerchantsScreen
import cd.ctm.agent.ui.screens.PaymentScreen
import cd.ctm.agent.ui.screens.ReviewsScreen
import cd.ctm.agent.ui.screens.SimulatorScreen
import cd.ctm.agent.ui.screens.SimsScreen
import cd.ctm.agent.ui.screens.StockScreen
import cd.ctm.agent.ui.screens.SyncQueueScreen

private data class TopNavItem(
    val label: String,
    val icon: ImageVector,
    val route: Route,
    val badgeCount: Int = 0,
)

@Composable
fun CtmNavApp(container: AppContainer) {
    val navController = rememberNavController()
    var sessionId by remember { mutableIntStateOf(0) }

    val loginVm = remember(sessionId) { container.newLoginViewModel() }
    val customerVm = remember(sessionId) { container.newCustomerViewModel() }
    val paymentVm = remember(sessionId) { container.newPaymentViewModel() }
    val deliveryVm = remember(sessionId) { container.newDeliveryViewModel() }
    val contractVm = remember(sessionId) { container.newContractViewModel() }
    val payInstructionsVm = remember(sessionId) { container.newPayInstructionsViewModel() }

    val dash by container.dashboard.collectAsState()
    val pending by container.pending.collectAsState()
    val readyDash = dash as? AppContainer.DashboardState.Ready
    val userRole = readyDash?.session?.role.orEmpty()
    val userId = readyDash?.session?.userId.orEmpty()

    val startDestination: Route = remember {
        if (container.tokens.isLoggedIn()) Route.Home else Route.Login
    }

    LaunchedEffect(sessionId) {
        if (container.tokens.isLoggedIn()) {
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

    val navItems = remember(userRole, pending) {
        if (userRole == "client") {
            listOf(
                TopNavItem("Accueil", Icons.Outlined.Home, Route.Home),
                TopNavItem("Mon crédit", Icons.Outlined.PhoneAndroid, Route.ClientPortal),
            )
        } else {
            listOf(
                TopNavItem("Accueil", Icons.Outlined.Home, Route.Home),
                TopNavItem("Client", Icons.Outlined.PersonAdd, Route.NewCustomer),
                TopNavItem("Livraison", Icons.Outlined.LocalShipping, Route.Delivery),
                TopNavItem("Espèces", Icons.Outlined.AccountBalanceWallet, Route.Payment),
                TopNavItem("Sync", Icons.Outlined.Sync, Route.SyncQueue, badgeCount = pending),
            )
        }
    }

    val showChrome = !isLoginScreen && currentDest != null && navItems.any { item ->
        when (item.route) {
            is Route.Home -> currentDest.hasRoute<Route.Home>()
            is Route.NewCustomer -> currentDest.hasRoute<Route.NewCustomer>()
            is Route.Delivery -> currentDest.hasRoute<Route.Delivery>()
            is Route.Payment -> currentDest.hasRoute<Route.Payment>()
            is Route.SyncQueue -> currentDest.hasRoute<Route.SyncQueue>()
            is Route.ClientPortal -> currentDest.hasRoute<Route.ClientPortal>()
            else -> false
        }
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
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTab(item.route) },
                                icon = {
                                    if (item.badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(item.badgeCount.toString()) } }) {
                                            Icon(item.icon, contentDescription = item.label)
                                        }
                                    } else {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                },
                                label = { Text(item.label, maxLines = 1) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (showChrome && isWide) {
                    NavigationRail(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        navItems.forEach { item ->
                            val selected = isRouteActive(currentDest, item.route)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTab(item.route) },
                                icon = {
                                    if (item.badgeCount > 0) {
                                        BadgedBox(badge = { Badge { Text(item.badgeCount.toString()) } }) {
                                            Icon(item.icon, contentDescription = item.label)
                                        }
                                    } else {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                },
                                label = { Text(item.label, maxLines = 1) },
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
                                backOffice = container.backOffice,
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
                                CtmScreenContainer(title = "Simulateur", onBack = { navController.popBackStack() }) {
                                    CtmAlertBanner(
                                        AlertKind.WARN,
                                        "Catalogue ou politique indisponible. Actualisez l'accueil avec un compte agent.",
                                    )
                                }
                            } else {
                                val appVm = remember(policy, sessionId) { container.newApplicationViewModel(policy) }
                                SimulatorScreen(
                                    items = readyDash.items,
                                    policy = policy,
                                    vm = appVm,
                                    customerId = args.customerId,
                                    onBack = { navController.popBackStack() },
                                )
                            }
                        }

                        composable<Route.NewCustomer> {
                            CustomerScreen(
                                vm = customerVm,
                                canMakeApplication = readyDash != null && readyDash.calculatorOk && readyDash.items.isNotEmpty(),
                                onApplication = { customerId ->
                                    navController.navigate(Route.Simulator(customerId))
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

                        composable<Route.Payment> {
                            PaymentScreen(
                                vm = paymentVm,
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

                        composable<Route.Applications> {
                            ApplicationsScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Reviews> {
                            ReviewsScreen(container, myUserId = userId, onBack = { navController.popBackStack() })
                        }

                        composable<Route.CreditPolicy> {
                            CreditPolicyScreen(container, role = userRole, onBack = { navController.popBackStack() })
                        }

                        composable<Route.ManualMobileMoney> {
                            ManualMobileMoneyScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.CashConfirm> {
                            CashConfirmScreen(container, myUserId = userId, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Merchants> {
                            MerchantsScreen(container, role = userRole, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Devices> {
                            DevicesScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Sims> {
                            SimsScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.DevicePolicy> {
                            DevicePolicyScreen(container, role = userRole, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Stock> {
                            StockScreen(container, role = userRole, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Collections> {
                            CollectionsScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.ContractsRelief> {
                            ContractsReliefScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.Approvals> {
                            ApprovalsScreen(container, onBack = { navController.popBackStack() })
                        }

                        composable<Route.ClientPortal> {
                            ClientPortalScreen(
                                contractVm = contractVm,
                                payVm = payInstructionsVm,
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
        is Route.ClientPortal -> dest.hasRoute<Route.ClientPortal>()
        else -> false
    }
}
