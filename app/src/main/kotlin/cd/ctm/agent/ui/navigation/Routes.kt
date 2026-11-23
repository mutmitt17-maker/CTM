package cd.ctm.agent.ui.navigation

import kotlinx.serialization.Serializable

/** Routes typées pour l'application Agent CTM. */
sealed interface Route {
    @Serializable data object Login : Route
    @Serializable data object Home : Route
    @Serializable data class Simulator(val customerId: String? = null) : Route
    @Serializable data object NewCustomer : Route
    @Serializable data class Kyc(val customerId: String? = null) : Route
    @Serializable data object Portfolio : Route
    @Serializable data object Delivery : Route
    @Serializable data class Payment(val contractNumber: String? = null) : Route
    @Serializable data object SyncQueue : Route
    @Serializable data object Integrations : Route
}
