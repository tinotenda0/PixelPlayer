package com.theveloper.pixelplay.presentation.adaptive

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 width buckets. Compact is every phone held upright; Medium is a phone on its side or a
 * small tablet; Expanded is a real tablet, a desktop window or an unfolded foldable.
 */
enum class WindowWidthClass { Compact, Medium, Expanded }

/** Height buckets. A phone in landscape is [Compact] here even though it is wide. */
enum class WindowHeightClass { Compact, Medium, Expanded }

internal const val MEDIUM_WIDTH_BREAKPOINT_DP = 600
internal const val EXPANDED_WIDTH_BREAKPOINT_DP = 840
internal const val MEDIUM_HEIGHT_BREAKPOINT_DP = 480
internal const val EXPANDED_HEIGHT_BREAKPOINT_DP = 900

/** Most of a short landscape window belongs to the content, not to a decorative header. */
internal const val LANDSCAPE_HEADER_HEIGHT_FRACTION = 0.38f

/**
 * Everything the UI needs to decide how wide a window it is painting into.
 *
 * The deliberate rule across the app: **portrait is never restructured.** A portrait window always
 * gets the bottom navigation bar and the single-column pages the app shipped with, whatever the
 * device. Only the *amount* of content scales in portrait (grid columns, see [gridColumns]).
 * Landscape windows at [MEDIUM_WIDTH_BREAKPOINT_DP] and above get the side navigation and the
 * two-pane pages.
 */
@Immutable
data class AdaptiveInfo(
    val widthDp: Dp,
    val heightDp: Dp,
    val widthClass: WindowWidthClass,
    val heightClass: WindowHeightClass,
    val isLandscape: Boolean,
    /**
     * The user has collapsed the labelled sidebar down to an icon rail. Only meaningful where a
     * labelled sidebar would otherwise be shown, i.e. when [isExpandedLayout] is true.
     */
    val sideNavigationCollapsed: Boolean = false
) {
    /**
     * True when the window is wide enough, and in the right orientation, to swap the bottom bar for
     * a side rail and to split detail pages into two panes.
     */
    val isWideLayout: Boolean
        get() = isLandscape && widthClass != WindowWidthClass.Compact

    /**
     * True only for a genuinely large landscape window — a tablet, not a phone on its side. The rail
     * shows labels and becomes a permanent sidebar here. A phone in landscape is ~915x411dp: wide
     * enough for the rail, far too short to spend 240dp of width on a labelled drawer.
     */
    val isExpandedLayout: Boolean
        get() = isWideLayout &&
            widthClass == WindowWidthClass.Expanded &&
            heightClass != WindowHeightClass.Compact

    /** Side navigation replaces the bottom bar. */
    val useSideNavigation: Boolean get() = isWideLayout

    /**
     * The side navigation is a labelled permanent sidebar rather than an icon-only rail. Note this
     * is deliberately *not* the same as [isExpandedLayout]: the window can be big enough for a
     * sidebar while the user has chosen to collapse it.
     */
    val usePermanentSidebar: Boolean get() = isExpandedLayout && !sideNavigationCollapsed

    /**
     * Whether the collapse toggle is worth offering. An icon rail on a phone in landscape is
     * already as small as it gets, so there is nothing to collapse.
     */
    val canCollapseSideNavigation: Boolean get() = isExpandedLayout

    /** Album / artist / playlist detail and settings split into a hero pane and a content pane. */
    val useTwoPaneDetail: Boolean get() = isWideLayout

    /** How wide the side navigation is, or zero when the bottom bar is in use. */
    val sideNavigationWidth: Dp
        get() = when {
            usePermanentSidebar -> PermanentSidebarWidth
            useSideNavigation -> NavigationRailWidth
            else -> 0.dp
        }

    /**
     * Comfortable reading width for single-column text content (settings bodies, about pages). Long
     * lines across a 1280dp tablet are the single most common way a stretched phone layout gives
     * itself away.
     */
    val readableContentMaxWidth: Dp
        get() = when (widthClass) {
            WindowWidthClass.Compact -> Dp.Unspecified
            WindowWidthClass.Medium -> 640.dp
            WindowWidthClass.Expanded -> 760.dp
        }

    /**
     * Width of a detail page's hero pane. About a third of the page area, bounded so it stays
     * comfortable on both a phone on its side and a large tablet.
     */
    val detailHeroPaneWidth: Dp
        get() = ((widthDp - sideNavigationWidth) * 0.34f).coerceIn(280.dp, 420.dp)

    /**
     * Symmetric inset that keeps a long single-column list centred and readable.
     *
     * A song row stretched across a tablet leaves its title at one edge and its overflow button at
     * the other, with a hand's width of nothing between them. Returns zero whenever the window is
     * already narrower than [maxWidth], so phones are untouched.
     */
    fun listCenteringInset(maxWidth: Dp = 760.dp): Dp {
        val available = widthDp - sideNavigationWidth
        return ((available - maxWidth) / 2).coerceAtLeast(0.dp)
    }

    /** Page gutters grow with the window so content is not pinned to the bezels. */
    val horizontalPagePadding: Dp
        get() = when (widthClass) {
            WindowWidthClass.Compact -> 16.dp
            WindowWidthClass.Medium -> 24.dp
            WindowWidthClass.Expanded -> 32.dp
        }

    /**
     * Caps a collapsing header's expanded height against the window.
     *
     * The shipped headers are 170-300dp, sized for a ~900dp-tall portrait window. A phone in
     * landscape is only ~411dp tall, where a 300dp header leaves barely a hundred pixels of list.
     * In landscape the header is therefore limited to [LANDSCAPE_HEADER_HEIGHT_FRACTION] of the
     * window, and portrait is passed through untouched.
     *
     * @param minHeight the header's collapsed height. The result always clears it by a margin: the
     *   collapse fraction these headers compute divides by (max - min), so letting the two meet
     *   would produce a NaN and take the whole header with it.
     */
    fun collapsingHeaderHeight(preferred: Dp, minHeight: Dp): Dp {
        if (!isLandscape) return preferred
        val budget = heightDp * LANDSCAPE_HEADER_HEIGHT_FRACTION
        return minOf(preferred, budget).coerceAtLeast(minHeight + 24.dp)
    }

    /**
     * Column count for a grid of square-ish media cards. Driven by raw width and *not* gated on
     * orientation, so a tablet held upright still fills its grid properly even though its page
     * structure is left alone.
     *
     * @param minCellWidth the narrowest a cell may be before a column is dropped.
     * @param min floor on the result, so a narrow window never collapses below the shipped layout.
     */
    fun gridColumns(minCellWidth: Dp = 180.dp, min: Int = 2, max: Int = 8): Int {
        val available = widthDp - sideNavigationWidth - horizontalPagePadding * 2
        if (available <= 0.dp || minCellWidth <= 0.dp) return min
        val fits = (available / minCellWidth).toInt()
        return fits.coerceIn(min, max)
    }
}

/** Width of the icon-only navigation rail. */
val NavigationRailWidth: Dp = 88.dp

/** Width of the labelled permanent sidebar shown on tablets. */
val PermanentSidebarWidth: Dp = 240.dp

private val DefaultAdaptiveInfo = AdaptiveInfo(
    widthDp = 411.dp,
    heightDp = 915.dp,
    widthClass = WindowWidthClass.Compact,
    heightClass = WindowHeightClass.Expanded,
    isLandscape = false
)

/**
 * Window metrics for the current composition. Provided once at the activity root by
 * [ProvideAdaptiveInfo]; reading it anywhere below is free.
 */
val LocalAdaptiveInfo: ProvidableCompositionLocal<AdaptiveInfo> =
    compositionLocalOf { DefaultAdaptiveInfo }

/** Shorthand for `LocalAdaptiveInfo.current`. */
val adaptive: AdaptiveInfo
    @Composable
    @ReadOnlyComposable
    get() = LocalAdaptiveInfo.current

/** Derives [AdaptiveInfo] from the current [Configuration]. */
@Composable
fun rememberAdaptiveInfo(): AdaptiveInfo {
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    return remember(widthDp, heightDp, isLandscape) {
        AdaptiveInfo(
            widthDp = widthDp.dp,
            heightDp = heightDp.dp,
            widthClass = widthClassFor(widthDp),
            heightClass = heightClassFor(heightDp),
            isLandscape = isLandscape
        )
    }
}

internal fun widthClassFor(widthDp: Int): WindowWidthClass = when {
    widthDp < MEDIUM_WIDTH_BREAKPOINT_DP -> WindowWidthClass.Compact
    widthDp < EXPANDED_WIDTH_BREAKPOINT_DP -> WindowWidthClass.Medium
    else -> WindowWidthClass.Expanded
}

internal fun heightClassFor(heightDp: Int): WindowHeightClass = when {
    heightDp < MEDIUM_HEIGHT_BREAKPOINT_DP -> WindowHeightClass.Compact
    heightDp < EXPANDED_HEIGHT_BREAKPOINT_DP -> WindowHeightClass.Medium
    else -> WindowHeightClass.Expanded
}
