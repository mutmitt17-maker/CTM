package cd.ctm.agent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import cd.ctm.agent.AppContainer
import cd.ctm.agent.R
import cd.ctm.agent.ui.components.AlertKind
import cd.ctm.agent.ui.components.CtmAlertBanner
import cd.ctm.agent.ui.components.CtmCard
import cd.ctm.agent.ui.components.CtmConfirmDialog
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
    val context = LocalContext.current
    val dash by container.dashboard.collectAsState()
    val pending by container.pending.collectAsState()
    val pendingPhotos by container.pendingPhotos.collectAsState()
    val otherAgentPending by container.otherAgentPending.collectAsState()
    val syncMessage by container.syncMessage.collectAsState()
    val syncRunning by container.syncRunning.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }
    var askedNotifPermission by rememberSaveable { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    LaunchedEffect(pending, pendingPhotos) {
        if (!askedNotifPermission && (pending > 0 || pendingPhotos > 0) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                askedNotifPermission = true
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    if (confirmLogout) {
        CtmConfirmDialog(
            title = stringResource(R.string.logout_pending_title),
            message = stringResource(R.string.logout_pending_message, pending, pendingPhotos),
            danger = true,
            onDismiss = { confirmLogout = false },
            onConfirm = {
                confirmLogout = false
                onLogout()
            },
        )
    }

    CtmScreenContainer(
        title = stringResource(R.string.home_title),
        subtitle = stringResource(R.string.home_subtitle),
        actions = {
            IconButton(
                onClick = { container.loadDashboard() },
                modifier = Modifier.testTag("refresh_dashboard_button"),
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = stringResource(R.string.action_refresh))
            }
            IconButton(
                onClick = {
                    if (pending > 0 || pendingPhotos > 0) confirmLogout = true else onLogout()
                },
                modifier = Modifier.testTag("logout_button"),
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = stringResource(R.string.logout_button))
            }
        },
    ) {
        when (val s = dash) {
            is AppContainer.DashboardState.Loading -> CtmLoadingView(stringResource(R.string.home_loading))
            is AppContainer.DashboardState.Error -> {
                CtmAlertBanner(AlertKind.ERROR, s.message)
                Button(
                    onClick = { container.loadDashboard() },
                    modifier = Modifier.fillMaxWidth().testTag("retry_dashboard_button"),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
            is AppContainer.DashboardState.Ready -> {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.fillMaxWidth(),
                    color = BrandTealDeep,
                ) {
                    Box {
                        Image(
                            painter = painterResource(id = R.drawable.img_ctm_hero),
                            contentDescription = stringResource(R.string.hero_banner_desc),
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
                                text = stringResource(R.string.home_welcome),
                                style = MaterialTheme.typography.headlineMedium,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            )
                            Text(
                                text = s.session.agentName.ifBlank { stringResource(R.string.home_default_agent) } +
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
                    CtmAlertBanner(AlertKind.INFO, stringResource(R.string.home_offline_cache_notice))
                }

                if (s.catalogueError != null && s.policy == null) {
                    CtmAlertBanner(AlertKind.WARN, s.catalogueError)
                }

                if (s.policy != null && !s.calculatorOk) {
                    CtmAlertBanner(
                        AlertKind.ERROR,
                        stringResource(R.string.home_calculator_mismatch),
                    )
                }

                if (otherAgentPending > 0) {
                    CtmAlertBanner(
                        AlertKind.WARN,
                        stringResource(R.string.home_other_agent_pending_warn, otherAgentPending),
                    )
                }

                val agentTiles = listOf(
                    HomeTile(
                        title = stringResource(R.string.home_tile_simulator_title),
                        desc = stringResource(
                            if (s.policy != null && s.items.isNotEmpty()) R.string.home_tile_simulator_desc
                            else R.string.home_tile_simulator_disabled,
                        ),
                        icon = Icons.Outlined.Calculate,
                        route = Route.Simulator(null),
                        tag = "tile_simulator",
                        enabled = s.policy != null && s.calculatorOk && s.items.isNotEmpty(),
                    ),
                    HomeTile(
                        title = stringResource(R.string.home_tile_customer_title),
                        desc = stringResource(R.string.home_tile_customer_desc),
                        icon = Icons.Outlined.PersonAdd,
                        route = Route.NewCustomer,
                        tag = "tile_new_customer",
                    ),
                    HomeTile(
                        title = stringResource(R.string.home_tile_kyc_title),
                        desc = stringResource(R.string.home_tile_kyc_desc),
                        icon = Icons.Outlined.Badge,
                        route = Route.Kyc(null),
                        tag = "tile_kyc",
                    ),
                    HomeTile(
                        title = stringResource(R.string.home_tile_portfolio_title),
                        desc = stringResource(R.string.home_tile_portfolio_desc),
                        icon = Icons.Outlined.FolderShared,
                        route = Route.Portfolio,
                        tag = "tile_portfolio",
                    ),
                    HomeTile(
                        title = stringResource(R.string.home_tile_delivery_title),
                        desc = stringResource(R.string.home_tile_delivery_desc),
                        icon = Icons.Outlined.LocalShipping,
                        route = Route.Delivery,
                        tag = "tile_delivery",
                    ),
                    HomeTile(
                        title = stringResource(R.string.home_tile_payment_title),
                        desc = stringResource(R.string.home_tile_payment_desc),
                        icon = Icons.Outlined.AccountBalanceWallet,
                        route = Route.Payment(null),
                        tag = "tile_payment",
                    ),
                )
                TileGroup(stringResource(R.string.home_section_operations), agentTiles, onNavigate)

                CtmCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.home_sync_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                buildString {
                                    append("$pending action(s) en attente d'envoi")
                                    if (pendingPhotos > 0) append(" · $pendingPhotos photo(s)")
                                    append(".")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(
                            onClick = { onNavigate(Route.SyncQueue) },
                            modifier = Modifier.testTag("open_sync_queue_button"),
                        ) {
                            Text(stringResource(R.string.home_sync_see_queue))
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
                        Text(stringResource(if (syncRunning) R.string.sync_running else R.string.sync_now))
                    }
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
