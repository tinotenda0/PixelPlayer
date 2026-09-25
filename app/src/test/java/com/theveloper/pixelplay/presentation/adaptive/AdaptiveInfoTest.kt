package com.theveloper.pixelplay.presentation.adaptive

import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The layout of every page hangs off these flags, so the device buckets are pinned here rather than
 * left to be rediscovered on a physical tablet.
 */
class AdaptiveInfoTest {

    private fun info(widthDp: Int, heightDp: Int): AdaptiveInfo = AdaptiveInfo(
        widthDp = widthDp.dp,
        heightDp = heightDp.dp,
        widthClass = widthClassFor(widthDp),
        heightClass = heightClassFor(heightDp),
        isLandscape = widthDp > heightDp
    )

    // Representative windows.
    private val phonePortrait = info(411, 915)
    private val phoneLandscape = info(915, 411)
    private val smallPhoneLandscape = info(780, 360)
    private val tabletPortrait = info(800, 1280)
    private val tabletLandscape = info(1280, 800)
    private val splitScreenLandscape = info(520, 400)

    @Test
    fun `portrait windows are never restructured`() {
        for (window in listOf(phonePortrait, tabletPortrait)) {
            assertFalse(window.useSideNavigation, "portrait must keep the bottom bar")
            assertFalse(window.useTwoPaneDetail, "portrait must keep single-pane detail pages")
            assertFalse(window.isExpandedLayout)
            assertEquals(0.dp, window.sideNavigationWidth)
        }
    }

    @Test
    fun `landscape phones get the icon rail, not the labelled sidebar`() {
        // A phone on its side is wide enough to look like a tablet by width alone; the height
        // check is what keeps a 240dp labelled sidebar off a 411dp-tall window.
        assertTrue(phoneLandscape.useSideNavigation)
        assertFalse(phoneLandscape.usePermanentSidebar)
        assertEquals(NavigationRailWidth, phoneLandscape.sideNavigationWidth)

        assertTrue(smallPhoneLandscape.useSideNavigation)
        assertFalse(smallPhoneLandscape.usePermanentSidebar)
    }

    @Test
    fun `landscape tablets get the labelled sidebar`() {
        assertTrue(tabletLandscape.useSideNavigation)
        assertTrue(tabletLandscape.usePermanentSidebar)
        assertTrue(tabletLandscape.useTwoPaneDetail)
        assertEquals(PermanentSidebarWidth, tabletLandscape.sideNavigationWidth)
    }

    @Test
    fun `collapsing the sidebar falls back to the icon rail and returns its width`() {
        val collapsed = tabletLandscape.copy(sideNavigationCollapsed = true)

        assertFalse(collapsed.usePermanentSidebar, "collapsed means no labels")
        assertEquals(NavigationRailWidth, collapsed.sideNavigationWidth)
        // Collapsing must not drop the user out of the wide layout entirely.
        assertTrue(collapsed.useSideNavigation)
        assertTrue(collapsed.useTwoPaneDetail)
        assertTrue(collapsed.canCollapseSideNavigation, "the toggle has to stay reachable")
    }

    @Test
    fun `collapsing the sidebar gives the reclaimed width back to content`() {
        val expanded = tabletLandscape
        val collapsed = tabletLandscape.copy(sideNavigationCollapsed = true)

        assertTrue(
            collapsed.gridColumns(minCellWidth = 190.dp) >= expanded.gridColumns(minCellWidth = 190.dp),
            "a narrower rail should never fit fewer cards"
        )
        assertTrue(
            collapsed.listCenteringInset() > expanded.listCenteringInset(),
            "centred lists should re-centre against the wider content area"
        )
    }

    @Test
    fun `there is nothing to collapse where no sidebar is offered`() {
        assertFalse(phoneLandscape.canCollapseSideNavigation, "phone landscape is already a rail")
        assertFalse(phonePortrait.canCollapseSideNavigation)
        assertFalse(tabletPortrait.canCollapseSideNavigation)

        // A stale collapse flag must not disturb a window that has no side navigation at all.
        val portraitWithStaleFlag = phonePortrait.copy(sideNavigationCollapsed = true)
        assertEquals(0.dp, portraitWithStaleFlag.sideNavigationWidth)
        assertFalse(portraitWithStaleFlag.useSideNavigation)
    }

    @Test
    fun `windows too narrow for two panes keep the single-pane layout`() {
        // Split screen: landscape, but not wide enough to divide further.
        assertFalse(splitScreenLandscape.useSideNavigation)
        assertFalse(splitScreenLandscape.useTwoPaneDetail)
    }

    @Test
    fun `grid columns grow with width and never drop below the shipped two`() {
        assertEquals(2, phonePortrait.gridColumns(minCellWidth = 190.dp))
        assertTrue(tabletPortrait.gridColumns(minCellWidth = 190.dp) >= 3)
        assertTrue(
            tabletLandscape.gridColumns(minCellWidth = 190.dp) >
                tabletPortrait.gridColumns(minCellWidth = 190.dp),
            "a wider window should fit more cards"
        )
    }

    @Test
    fun `grid columns account for the space the sidebar takes`() {
        // Same raw width, but the sidebar is not available to the grid.
        val withoutSidebar = info(1280, 1400) // portrait, no side nav
        assertTrue(
            tabletLandscape.gridColumns(minCellWidth = 190.dp) <
                withoutSidebar.gridColumns(minCellWidth = 190.dp),
            "the sidebar's width must be subtracted before dividing into columns"
        )
    }

    @Test
    fun `collapsing headers are capped in landscape but untouched in portrait`() {
        val minHeight = 88.dp

        assertEquals(300.dp, phonePortrait.collapsingHeaderHeight(300.dp, minHeight))
        assertEquals(300.dp, tabletPortrait.collapsingHeaderHeight(300.dp, minHeight))

        val capped = phoneLandscape.collapsingHeaderHeight(300.dp, minHeight)
        assertTrue(capped < 300.dp, "a 300dp header does not belong in a 411dp-tall window")
        assertTrue(capped > minHeight, "header must stay above its collapsed height")
    }

    @Test
    fun `a capped header never collapses onto its minimum height`() {
        // The screens compute a collapse fraction as (height - min) / (max - min); if the cap ever
        // returned exactly minHeight that divides by zero and the header renders as NaN.
        val veryShort = info(900, 200)
        val minHeight = 120.dp
        val result = veryShort.collapsingHeaderHeight(300.dp, minHeight)
        assertTrue(result > minHeight, "expected a non-zero collapse range, got $result vs $minHeight")
    }

    @Test
    fun `width and height buckets fall on the documented breakpoints`() {
        assertEquals(WindowWidthClass.Compact, widthClassFor(599))
        assertEquals(WindowWidthClass.Medium, widthClassFor(600))
        assertEquals(WindowWidthClass.Medium, widthClassFor(839))
        assertEquals(WindowWidthClass.Expanded, widthClassFor(840))

        assertEquals(WindowHeightClass.Compact, heightClassFor(479))
        assertEquals(WindowHeightClass.Medium, heightClassFor(480))
        assertEquals(WindowHeightClass.Expanded, heightClassFor(900))
    }
}
