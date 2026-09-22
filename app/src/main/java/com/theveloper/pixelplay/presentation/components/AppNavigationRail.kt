package com.theveloper.pixelplay.presentation.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.theveloper.pixelplay.BottomNavItem
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.navigation.navigateToTopLevelSafely
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Side navigation for wide windows: an icon rail on a phone held sideways, the same rail widened
 * into a labelled sidebar on a tablet. It replaces the bottom bar entirely - the two are never on
 * screen together.
 *
 * @param expanded show labels and the app header. Driven by
 *   [com.theveloper.pixelplay.presentation.adaptive.AdaptiveInfo.usePermanentSidebar].
 */
@Composable
fun AppNavigationRail(
    navController: NavHostController,
    navItems: ImmutableList<BottomNavItem>,
    currentRoute: String?,
    expanded: Boolean,
    width: Dp,
    onSearchIconDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** Whether to offer the collapse toggle at all. See [AdaptiveInfo.canCollapseSideNavigation]. */
    collapsible: Boolean = false,
    onToggleCollapsed: () -> Unit = {},
    /**
     * How far the rail is hidden, 0..1, driven by the full player taking over the window.
     *
     * The rail keeps its own width and slides out rather than shrinking, so its contents never
     * squeeze on the way. Read in the draw phase, so opening the player does not recompose it.
     */
    hiddenFractionProvider: () -> Float = { 0f }
) {
    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .graphicsLayer {
                val fraction = hiddenFractionProvider().coerceIn(0f, 1f)
                translationX = -size.width * fraction
                alpha = 1f - fraction
            },
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                // The rail owns the start edge, so it absorbs the status bar, the gesture inset and
                // any display cutout on that side. The Surface still paints edge to edge behind
                // them; only the items are inset.
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Start + WindowInsetsSides.Vertical
                    )
                )
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (expanded) 12.dp else 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SidebarHeader(
                expanded = expanded,
                collapsible = collapsible,
                onToggleCollapsed = onToggleCollapsed
            )

            val scope = rememberCoroutineScope()
            var lastSearchTapTimestamp by remember { mutableStateOf(0L) }
            val latestCurrentRoute by rememberUpdatedState(currentRoute)
            val latestOnSearchIconDoubleTap by rememberUpdatedState(onSearchIconDoubleTap)
            val navigationEnabled = currentRoute != null

            navItems.forEach { item ->
                val isSelected = currentRoute != null && currentRoute == item.screen.route
                val iconRes =
                    if (isSelected && item.selectedIconResId != null && item.selectedIconResId != 0) {
                        item.selectedIconResId
                    } else {
                        item.iconResId
                    }
                val label = stringResource(id = item.labelResId)

                SideNavItem(
                    label = label,
                    expanded = expanded,
                    selected = isSelected,
                    enabled = navigationEnabled,
                    icon = {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = if (expanded) null else label,
                            modifier = Modifier.size(24.dp)
                        )
                    },
                    onClick = click@{
                        if (!navigationEnabled) {
                            lastSearchTapTimestamp = 0L
                            return@click
                        }
                        val itemRoute = item.screen.route
                        val isAlreadySelected = latestCurrentRoute == itemRoute

                        // Search keeps the double-tap-to-focus affordance the bottom bar has.
                        if (itemRoute == Screen.Search.route) {
                            val now = SystemClock.elapsedRealtime()
                            val isDoubleTap = now - lastSearchTapTimestamp <= 350L
                            lastSearchTapTimestamp = now

                            if (!isAlreadySelected &&
                                !navController.navigateToTopLevelSafely(itemRoute)
                            ) {
                                lastSearchTapTimestamp = 0L
                                return@click
                            }
                            if (isDoubleTap) {
                                lastSearchTapTimestamp = 0L
                                if (isAlreadySelected) {
                                    latestOnSearchIconDoubleTap()
                                } else {
                                    scope.launch {
                                        delay(160L)
                                        latestOnSearchIconDoubleTap()
                                    }
                                }
                            }
                        } else {
                            lastSearchTapTimestamp = 0L
                            if (!isAlreadySelected) {
                                navController.navigateToTopLevelSafely(itemRoute)
                            }
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Secondary destinations. In a wide window the modal drawer is never opened, so the
            // rail is the only route to Equalizer, Downloads and Settings.
            SideNavItem(
                label = stringResource(R.string.settings_category_equalizer_title),
                expanded = expanded,
                selected = currentRoute == Screen.Equalizer.route,
                enabled = true,
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                onClick = {
                    if (currentRoute != Screen.Equalizer.route) {
                        navController.navigateSafely(Screen.Equalizer.route)
                    }
                }
            )
            SideNavItem(
                label = stringResource(R.string.downloads_title),
                expanded = expanded,
                selected = currentRoute == Screen.Downloads.route,
                enabled = true,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.rounded_download_24),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                onClick = {
                    if (currentRoute != Screen.Downloads.route) {
                        navController.navigateSafely(Screen.Downloads.route)
                    }
                }
            )

            Spacer(modifier = Modifier.weight(1f, fill = true))

            SideNavItem(
                label = stringResource(R.string.common_settings),
                expanded = expanded,
                selected = currentRoute == Screen.Settings.route,
                enabled = true,
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.rounded_settings_24),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                onClick = {
                    if (currentRoute != Screen.Settings.route) {
                        navController.navigateSafely(Screen.Settings.route)
                    }
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Rail header. Expanded it shows the app name with the collapse toggle beside it; collapsed it is
 * just the toggle, which is the only way back to the labelled sidebar.
 */
@Composable
private fun SidebarHeader(
    expanded: Boolean,
    collapsible: Boolean,
    onToggleCollapsed: () -> Unit
) {
    val toggle: @Composable () -> Unit = {
        IconButton(onClick = onToggleCollapsed) {
            Icon(
                painter = painterResource(R.drawable.rounded_menu_24),
                contentDescription = stringResource(
                    if (expanded) R.string.nav_rail_collapse else R.string.nav_rail_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.app_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (collapsible) toggle()
        }
    } else {
        if (collapsible) {
            toggle()
            Spacer(modifier = Modifier.height(4.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * One navigation target. Stacks icon over label in a pill when the rail is narrow and lays them out
 * in a row when it is expanded, so the selection indicator reads the same either way.
 */
@Composable
private fun SideNavItem(
    label: String,
    expanded: Boolean,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        animationSpec = tween(durationMillis = 220),
        label = "SideNavItemContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            selected -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(durationMillis = 220),
        label = "SideNavItemContent"
    )
    val shape = RoundedCornerShape(if (expanded) 20.dp else 16.dp)

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.Tab,
                onClick = onClick
            )
    ) {
        if (expanded) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                icon()
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                icon()
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
