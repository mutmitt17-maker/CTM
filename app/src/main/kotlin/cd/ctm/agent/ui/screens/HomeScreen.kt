package cd.ctm.agent.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Percent
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmEmptyState
import cd.ctm.agent.ui.components.CtmLoadingView
import cd.ctm.agent.ui.components.CtmScreenContainer
import cd.ctm.agent.ui.components.roleLabel
import cd.ctm.agent.ui.components.statusInfo
import cd.ctm.agent.ui.navigation.Route
import cd.ctm.agent.ui.theme.BrandTeal
import cd.ctm.agent.ui.theme.BrandTealDeep

private data class HomeTile(
    val title: String,
    val desc: String,
    val icon: ImageVector,
    val route: Route,
    val tag: String,
    val enabled: Boolean = true,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onNavigate: (Route) -> Unit,
    onLogout: () -> Unit,
    onNeedsLogin: () -> Unit,
) {
    val dash by container.dashboard.collectAsState()
    val pending by container.pending.collectAsState()
    val syncMessage by container.syncMessage.collectAsState()
    val syncRunning by container.syncRunning.collectAsState()

    CtmScreenContainer(
        title = "CTM · Accueil",
        subtitle = "Crédit Téléphone",
        actions = {
            IconButton(
                onClick = { container.loadDashboard() },
                modifier = Modifier.testTag("refresh_dashboard_button"),
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = "Actualiser")
            }
            IconButton(
                onClick = onLogout,
                modifier = Modifier.testTag("logout_button"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = "Se déconnecter")
            }
        },
    ) {
        when (val s = dash) {
            is AppContainer.DashboardState.Loading -> CtmLoadingView("Chargement de la session et du catalogue…")
            is AppContainer.DashboardState.Error -> {
                CtmAlertBanner(AlertKind.ERROR, s.message)
                Button(
                    onClick = { container.loadDashboard() },
                    modifier = Modifier.fillMaxWidth().testTag("retry_dashboard_button"),
                ) {
                    Text("Réessayer")
                }
            }
            is AppContainer.DashboardState.Ready -> {
                // Hero card with generated banner artwork
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                    color = BrandTealDeep,
                ) {
                    Box {
                        Image(
                            painter = painterResource(id = R.drawable.img_ctm_hero),
                            contentDescription = "Illustration CTM Crédit Téléphone",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .alpha(0.28f),
                        )
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            BrandTealDeep.copy(alpha = 0.88f),
                                            BrandTeal.copy(alpha = 0.72f),
                                        ),
                                    ),
                                ),
                        )
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = "Bienvenue",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Text(
                                text = s.session.agentName.ifBlank { "Utilisateur CTM" } +
                                    if (s.session.agencyName.isNotBlank()) " · ${s.session.agencyName}" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.9f),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                HeroChip(roleLabel(s.session.role))
                                HeroChip("Statut : ${statusInfo(s.session.status).first}")
                                if (s.policy != null) {
                                    HeroChip("${s.items.size} appareil(s) en stock")
                                }
                            }
                        }
                    }
                }

                if (s.fromCache) {
                    CtmAlertBanner(AlertKind.INFO, "Mode hors ligne actif : catalogue chargé depuis le cache local.")
                }

                if (s.policy != null && !s.calculatorOk) {
                    CtmAlertBanner(
                        AlertKind.ERROR,
                        "Simulation hors ligne désactivée : le calculateur du téléphone diverge du serveur. Contactez l'administrateur.",
                    )
                }

                // Client portal quick banner if client role
                if (s.session.role == "client") {
                    TileGroup(
                        title = "Mon crédit",
                        tiles = listOf(
                            HomeTile(
                                title = "Mon contrat & échéancier",
                                desc = "Consultez votre solde, vos échéances et les instructions Mobile Money.",
                                icon = Icons.Outlined.PhoneAndroid,
                                route = Route.ClientPortal,
                                tag = "tile_client_portal",
                            ),
                        ),
                        onNavigate = onNavigate,
                    )
                } else {
                    // Field agent operations
                    val agentTiles = buildList {
                        add(
                            HomeTile(
                                title = "Simulateur de crédit",
                                desc = if (s.policy != null && s.items.isNotEmpty()) {
                                    "Calculez une offre hors ligne : acompte, durée, échéances."
                                } else {
                                    "Catalogue agent indisponible pour ce compte ou stock vide."
                                },
                                icon = Icons.Outlined.Calculate,
                                route = Route.Simulator(null),
                                tag = "tile_simulator",
                                enabled = s.policy != null && s.calculatorOk && s.items.isNotEmpty(),
                            ),
                        )
                        add(
                            HomeTile(
                                title = "Nouveau client",
                                desc = "Enregistrez un client (+243) hors ligne et créez son dossier.",
                                icon = Icons.Outlined.PersonAdd,
                                route = Route.NewCustomer,
                                tag = "tile_new_customer",
                            ),
                        )
                        add(
                            HomeTile(
                                title = "Livraison téléphone",
                                desc = "IMEI, photo, signature de remise et enrôlement MDM en ligne.",
                                icon = Icons.Outlined.LocalShipping,
                                route = Route.Delivery,
                                tag = "tile_delivery",
                            ),
                        )
                        add(
                            HomeTile(
                                title = "Encaisser en espèces",
                                desc = "Enregistrez un paiement reçu par numéro de contrat.",
                                icon = Icons.Outlined.AccountBalanceWallet,
                                route = Route.Payment,
                                tag = "tile_payment",
                            ),
                        )
                    }
                    TileGroup("Opérations de terrain", agentTiles, onNavigate)

                    // Sync banner card
                    CtmCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Synchronisation hors ligne", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "$pending action(s) en attente d'envoi.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(
                                onClick = { onNavigate(Route.SyncQueue) },
                                modifier = Modifier.testTag("open_sync_queue_button"),
                            ) {
                                Text("Voir la file")
                            }
                        }
                        if (syncMessage.isNotBlank()) {
                            CtmAlertBanner(AlertKind.INFO, syncMessage)
                        }
                        Button(
                            onClick = { container.syncNow(onNeedsLogin) },
                            enabled = !syncRunning,
                            modifier = Modifier.fillMaxWidth().testTag("sync_now_button"),
                        ) {
                            Text(if (syncRunning) "Synchronisation…" else "Synchroniser maintenant")
                        }
                    }

                    // Back-Office groups (full parity with web/app.js)
                    TileGroup(
                        title = "Dossiers de crédit",
                        tiles = listOf(
                            HomeTile("Dossiers", "Consultez les 50 derniers dossiers de crédit.", Icons.Outlined.FolderOpen, Route.Applications, "tile_applications"),
                            HomeTile("Dossiers à décider", "Décidez des dossiers en révision manuelle.", Icons.AutoMirrored.Outlined.FactCheck, Route.Reviews, "tile_reviews"),
                            HomeTile("Politique de crédit", "Frais, intérêts, pénalités et seuils de score.", Icons.Outlined.Tune, Route.CreditPolicy, "tile_credit_policy"),
                        ),
                        onNavigate = onNavigate,
                    )
                    TileGroup(
                        title = "Paiements",
                        tiles = listOf(
                            HomeTile("Paiement manuel", "Enregistrez un paiement Mobile Money reçu.", Icons.Outlined.CreditCard, Route.ManualMobileMoney, "tile_manual_momo"),
                            HomeTile("Espèces à confirmer", "Confirmez les espèces encaissées par les agents.", Icons.Outlined.AccountBalanceWallet, Route.CashConfirm, "tile_cash_confirm"),
                            HomeTile("Numéros marchands", "Numéros où les clients envoient leurs paiements.", Icons.Outlined.Storefront, Route.Merchants, "tile_merchants"),
                        ),
                        onNavigate = onNavigate,
                    )
                    TileGroup(
                        title = "Appareils",
                        tiles = listOf(
                            HomeTile("Appareils gérés", "Verrouillez, déverrouillez et suivez les appareils.", Icons.Outlined.PhoneAndroid, Route.Devices, "tile_devices"),
                            HomeTile("SIM à valider", "Autorisez ou refusez les changements de carte SIM.", Icons.Outlined.SimCard, Route.Sims, "tile_sims"),
                            HomeTile("Politique appareils", "Règles de sécurité et fournisseur par marque.", Icons.Outlined.Security, Route.DevicePolicy, "tile_device_policy"),
                            HomeTile("Stock en magasin", "Affectez les téléphones en stock aux agences.", Icons.Outlined.Inventory2, Route.Stock, "tile_stock"),
                        ),
                        onNavigate = onNavigate,
                    )
                    TileGroup(
                        title = "Recouvrement",
                        tiles = listOf(
                            HomeTile("Tâches de recouvrement", "Relances et visites : assignation et clôture.", Icons.Outlined.PhoneInTalk, Route.Collections, "tile_collections"),
                            HomeTile("Remises & restructuration", "Demandez une remise de pénalités ou restructuration.", Icons.Outlined.Percent, Route.ContractsRelief, "tile_contracts_relief"),
                            HomeTile("Validations", "Double validation des demandes sensibles.", Icons.Outlined.Shield, Route.Approvals, "tile_approvals"),
                        ),
                        onNavigate = onNavigate,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroChip(label: String) {
    Surface(
        color = Color.White.copy(alpha = 0.18f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.30f)),
        shape = RoundedCornerShape(999.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun TileGroup(
    title: String,
    tiles: List<HomeTile>,
    onNavigate: (Route) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        tiles.forEach { t ->
            Surface(
                onClick = { if (t.enabled) onNavigate(t.route) },
                enabled = t.enabled,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(t.tag),
            ) {
                Row(
                    modifier = Modifier
                        .padding(14.dp)
                        .alpha(if (t.enabled) 1f else 0.5f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = t.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = t.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = t.desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
