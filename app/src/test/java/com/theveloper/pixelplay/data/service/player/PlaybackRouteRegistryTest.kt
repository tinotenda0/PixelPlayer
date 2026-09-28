package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PlaybackRouteRegistry")
class PlaybackRouteRegistryTest {

    private class FakeRoute(override val name: String) : PlaybackRoute {
        override val isActive: StateFlow<Boolean> = MutableStateFlow(true)
        override val state: StateFlow<RouteState> = MutableStateFlow(RouteState())
        override fun play() = Unit
        override fun pause() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setVolume(volume: Float) = Unit
        override fun setShuffle(enabled: Boolean) = Unit
        override fun setRepeat(mode: String) = Unit
    }

    private val registry = PlaybackRouteRegistry()

    @Test
    fun `starts with playback local`() {
        assertNull(registry.activeRoute.value)
    }

    @Test
    fun `registering takes the slot`() {
        val route = FakeRoute("Tablet")
        registry.register(route)
        assertSame(route, registry.activeRoute.value)
    }

    @Test
    fun `a second route replaces the first`() {
        // One slot on purpose: two things claiming to be what is playing is how the player
        // surface ends up contradicting itself.
        val cast = FakeRoute("Speaker")
        val handoff = FakeRoute("Phone")
        registry.register(cast)
        registry.register(handoff)
        assertSame(handoff, registry.activeRoute.value)
    }

    @Test
    fun `unregistering releases the slot`() {
        val route = FakeRoute("Phone")
        registry.register(route)
        registry.unregister(route)
        assertNull(registry.activeRoute.value)
    }

    @Test
    fun `a route that no longer holds the slot cannot release it`() {
        // Otherwise a route shutting down late would silently drop whoever replaced it.
        val old = FakeRoute("Speaker")
        val current = FakeRoute("Phone")
        registry.register(old)
        registry.register(current)

        registry.unregister(old)

        assertSame(current, registry.activeRoute.value)
    }
}
