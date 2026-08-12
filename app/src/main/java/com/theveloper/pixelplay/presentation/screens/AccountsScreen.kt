@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.navidrome.auth.NavidromeLoginActivity
import com.theveloper.pixelplay.presentation.viewmodel.AccountsUiState
import com.theveloper.pixelplay.presentation.viewmodel.AccountsViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import racra.compose.smooth_corner_rect_library.AbsoluteSmoothCornerShape

/**
 * PixelPlayer is a dedicated client for one XPS gateway account, so this screen shows a single
 * connected/disconnected identity rather than a generic multi-service list.
 */
@Composable
fun AccountsScreen(
    onBackClick: () -> Unit,
    onOpenNavidromeDashboard: () -> Unit = {},
    viewModel: AccountsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val lazyListState = rememberLazyListState()

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minTopBarHeight = 64.dp + statusBarHeight
    val maxTopBarHeight = 180.dp
    val minTopBarHeightPx = with(density) { minTopBarHeight.toPx() }
    val maxTopBarHeightPx = with(density) { maxTopBarHeight.toPx() }
    val topBarHeight = remember { Animatable(maxTopBarHeightPx) }
    var collapseFraction by remember { mutableStateOf(0f) }

    LaunchedEffect(topBarHeight.value) {
        collapseFraction =
            1f - (
                (topBarHeight.value - minTopBarHeightPx) /
                    (maxTopBarHeightPx - minTopBarHeightPx)
                ).coerceIn(0f, 1f)
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val isScrollingDown = delta < 0

                if (!isScrollingDown &&
                    (
                        lazyListState.firstVisibleItemIndex > 0 ||
                            lazyListState.firstVisibleItemScrollOffset > 0
                        )
                ) {
                    return Offset.Zero
                }

                val previousHeight = topBarHeight.value
                val newHeight = (previousHeight + delta).coerceIn(minTopBarHeightPx, maxTopBarHeightPx)
                val consumed = newHeight - previousHeight

                if (consumed.roundToInt() != 0) {
                    coroutineScope.launch { topBarHeight.snapTo(newHeight) }
                }

                val canConsumeScroll = !(isScrollingDown && newHeight == minTopBarHeightPx)
                return if (canConsumeScroll) Offset(0f, consumed) else Offset.Zero
            }
        }
    }

    LaunchedEffect(lazyListState.isScrollInProgress) {
        if (!lazyListState.isScrollInProgress) {
            val shouldExpand = topBarHeight.value > (minTopBarHeightPx + maxTopBarHeightPx) / 2
            val canExpand =
                lazyListState.firstVisibleItemIndex == 0 &&
                    lazyListState.firstVisibleItemScrollOffset == 0
            val targetValue = if (shouldExpand && canExpand) maxTopBarHeightPx else minTopBarHeightPx
            if (topBarHeight.value != targetValue) {
                coroutineScope.launch {
                    topBarHeight.animateTo(targetValue, spring(stiffness = Spring.StiffnessMedium))
                }
            }
        }
    }

    Box(modifier = Modifier.nestedScroll(nestedScrollConnection).fillMaxSize()) {
        val currentTopBarHeightDp = with(density) { topBarHeight.value.toDp() }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = currentTopBarHeightDp + 8.dp,
                start = 16.dp,
                end = 16.dp,
                bottom = MiniPlayerHeight + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { AccountsHeroSection(isConnected = uiState.isConnected) }

            item {
                if (uiState.isConnected) {
                    ConnectedGatewayCard(
                        uiState = uiState,
                        onManage = onOpenNavidromeDashboard,
                        onLogout = { viewModel.logout() }
                    )
                } else {
                    DisconnectedGatewayCard(
                        onConnect = {
                            safeStartActivity(
                                context = context,
                                intent = Intent(context, NavidromeLoginActivity::class.java)
                            )
                        }
                    )
                }
            }
        }

        CollapsibleCommonTopBar(
            title = stringResource(R.string.settings_category_accounts_title),
            collapseFraction = collapseFraction,
            headerHeight = currentTopBarHeightDp,
            onBackClick = onBackClick,
            expandedTitleStartPadding = 20.dp,
            collapsedTitleStartPadding = 68.dp
        )
    }
}

@Composable
private fun AccountsHeroSection(isConnected: Boolean) {
    val sectionShape = AbsoluteSmoothCornerShape(30.dp, 60)
    Card(
        shape = sectionShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GatewayIcon(modifier = Modifier.width(56.dp).height(46.dp))
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.accounts_connected_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.accounts_connected_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = AbsoluteSmoothCornerShape(12.dp, 60),
                color = if (isConnected) Color(0xFFE1F5FE) else MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Text(
                    text = stringResource(
                        if (isConnected) R.string.accounts_status_connected else R.string.accounts_no_linked_title
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isConnected) Color(0xFF0277BD) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ConnectedGatewayCard(
    uiState: AccountsUiState,
    onManage: () -> Unit,
    onLogout: () -> Unit
) {
    val loggingOut = stringResource(R.string.accounts_logging_out_status)
    val logOut = stringResource(R.string.cloud_cd_logout)
    val openService = stringResource(R.string.accounts_action_open_service)
    val cardShape = AbsoluteSmoothCornerShape(28.dp, 60)

    Card(
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GatewayIcon(modifier = Modifier.width(48.dp).height(40.dp))
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.auth_subsonic_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.username ?: stringResource(R.string.accounts_status_connected),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            uiState.syncedPlaylistsLabel?.let { label ->
                Surface(
                    shape = AbsoluteSmoothCornerShape(14.dp, 60),
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))

            FilledTonalButton(
                onClick = onManage,
                enabled = !uiState.isLoggingOut,
                shape = AbsoluteSmoothCornerShape(18.dp, 60),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(imageVector = Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = openService, fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onLogout,
                enabled = !uiState.isLoggingOut,
                shape = AbsoluteSmoothCornerShape(18.dp, 60),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                if (uiState.isLoggingOut) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = if (uiState.isLoggingOut) loggingOut else logOut, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DisconnectedGatewayCard(onConnect: () -> Unit) {
    Card(
        shape = AbsoluteSmoothCornerShape(28.dp, 60),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.accounts_no_linked_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.accounts_no_linked_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onConnect,
                shape = AbsoluteSmoothCornerShape(18.dp, 60),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_navidrome_md3),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.accounts_connect_service).format(stringResource(R.string.auth_subsonic_title)),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun GatewayIcon(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_subsonic),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(32.dp)
        )
        Icon(
            imageVector = ImageVector.vectorResource(id = R.drawable.ic_navidrome),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(32.dp).padding(start = 16.dp)
        )
    }
}

private fun safeStartActivity(
    context: android.content.Context,
    intent: Intent
) {
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, context.getString(R.string.accounts_unable_open_screen), Toast.LENGTH_SHORT).show()
        }
}
