package cd.ctm.agent.ui.navigation

import kotlinx.serialization.Serializable

/** Routes typées pour Jetpack Navigation Compose. */
sealed interface Route {
    @Serializable data object Login : Route
    @Serializable data object Home : Route

    // Opérations terrain
    @Serializable data class Simulator(val customerId: String? = null) : Route
    @Serializable data object NewCustomer : Route
    @Serializable data object Delivery : Route
    @Serializable data object Payment : Route
    @Serializable data object SyncQueue : Route

    // Back-Office (parité web/app.js)
    @Serializable data object Applications : Route
    @Serializable data object Reviews : Route
    @Serializable data object CreditPolicy : Route
    @Serializable data object ManualMobileMoney : Route
    @Serializable data object CashConfirm : Route
    @Serializable data object Merchants : Route
    @Serializable data object Devices : Route
    @Serializable data object Sims : Route
    @Serializable data object DevicePolicy : Route
    @Serializable data object Stock : Route
    @Serializable data object Collections : Route
    @Serializable data object ContractsRelief : Route
    @Serializable data object Approvals : Route

    // Portail Client
    @Serializable data object ClientPortal : Route
}
