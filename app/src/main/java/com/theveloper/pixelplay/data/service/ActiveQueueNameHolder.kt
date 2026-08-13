package com.theveloper.pixelplay.data.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The display name of whatever queue is currently playing. Set by [PlayerViewModel]'s
 * playSongs/playSongsShuffled whenever a new queue starts, read by [MusicService] during endless
 * queue extension to recognize special queues (e.g. Surprise Me) that need different extension
 * logic than "find songs similar to whatever's currently playing" — these two live in different
 * Hilt components (ViewModel vs. Service) with no other way to see each other's state.
 */
@Singleton
class ActiveQueueNameHolder @Inject constructor() {
    private val _name = MutableStateFlow<String?>(null)
    val name: StateFlow<String?> = _name.asStateFlow()

    fun set(queueName: String?) {
        _name.value = queueName
    }

    companion object {
        const val SURPRISE_ME = "Surprise Me"
    }
}
