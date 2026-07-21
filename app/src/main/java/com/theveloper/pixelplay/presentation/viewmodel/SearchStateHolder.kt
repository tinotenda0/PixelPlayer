package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.search.SearchRanker
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.FlowPreview

/**
 * Manages search state and operations.
 * Extracted from PlayerViewModel to improve modularity.
 *
 * Responsibilities:
 * - Search query execution
 * - Search filter management
 * - Search history CRUD operations
 */
@Singleton
class SearchStateHolder @Inject constructor(
    private val musicRepository: MusicRepository,
    private val navidromeRepository: com.theveloper.pixelplay.data.navidrome.NavidromeRepository,
) {

    /**
     * Merge live gateway song results (including on-demand "yt-" YouTube songs)
     * into the local search results, de-duped by song id. Only for filters that
     * show songs; other filters (albums/artists) are left untouched.
     */
    private fun mergeLiveSongs(
        local: List<SearchResultItem>,
        live: List<com.theveloper.pixelplay.data.model.Song>,
        filter: SearchFilterType
    ): List<SearchResultItem> {
        if (live.isEmpty()) return local
        if (filter != SearchFilterType.ALL && filter != SearchFilterType.SONGS) return local
        val existing = local.mapNotNull { (it as? SearchResultItem.SongItem)?.song?.id }.toHashSet()
        val liveItems = live
            .filterNot { it.id in existing }
            .map { SearchResultItem.SongItem(it) }
        return local + liveItems
    }
    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
    }

    private data class SearchRequest(
        val query: String,
        val requestId: Long,
    )

    // Search State
    private val _searchResults = MutableStateFlow<ImmutableList<SearchResultItem>>(persistentListOf())
    val searchResults = _searchResults.asStateFlow()

    private val _selectedSearchFilter = MutableStateFlow(SearchFilterType.ALL)
    val selectedSearchFilter = _selectedSearchFilter.asStateFlow()

    private val _searchHistory = MutableStateFlow<ImmutableList<SearchHistoryItem>>(persistentListOf())
    val searchHistory = _searchHistory.asStateFlow()

    private val searchRequests = MutableSharedFlow<SearchRequest>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val latestSearchRequestId = AtomicLong(0L)

    private var scope: CoroutineScope? = null
    private var searchJob: Job? = null

    /**
     * Initialize with ViewModel scope.
     */
    fun initialize(scope: CoroutineScope) {
        this.scope = scope
        observeSearchRequests()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchRequests() {
        searchJob?.cancel()
        searchJob = scope?.launch {
            searchRequests
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { request ->
                    val normalizedQuery = request.query

                    if (normalizedQuery.isBlank()) {
                        if (_searchResults.value.isNotEmpty()) {
                            _searchResults.value = persistentListOf()
                        }
                        return@collectLatest
                    }

                    try {
                        val currentFilter = _selectedSearchFilter.value

                        // Live on-demand search against the Subsonic/Navidrome gateway
                        // (which augments results with streamable "yt-" YouTube songs
                        // that are NOT in the synced library). Emits empty first so
                        // local results render instantly, then the live results when
                        // they arrive. Failures (not logged in, offline) → empty.
                        val liveSongsFlow = flow {
                            emit(emptyList<com.theveloper.pixelplay.data.model.Song>())
                            val live = runCatching {
                                navidromeRepository.searchSongs(normalizedQuery, limit = 40)
                                    .getOrDefault(emptyList())
                            }.getOrDefault(emptyList())
                            if (live.isNotEmpty()) emit(live)
                        }

                        musicRepository.searchAll(normalizedQuery, currentFilter)
                            .combine(liveSongsFlow) { local, live -> local to live }
                            .collect { (resultsList, liveSongs) ->
                                val merged = mergeLiveSongs(resultsList, liveSongs, _selectedSearchFilter.value)

                                // Relevance ranking: entities whose own name matches beat
                                // incidental matches, played songs rank first among equal
                                // matches, and spacing/typos are tolerated. See SearchRanker.
                                val songIds = merged.mapNotNull {
                                    (it as? SearchResultItem.SongItem)?.song?.id
                                }
                                val playStats = if (songIds.isEmpty()) {
                                    emptyMap()
                                } else {
                                    musicRepository.getSearchPlayStats(songIds)
                                }
                                val ranked = SearchRanker.rank(normalizedQuery, merged, playStats)

                                if (request.requestId != latestSearchRequestId.get()) {
                                    return@collect
                                }

                                val immutableResults = ranked.toImmutableList()
                                if (_searchResults.value != immutableResults) {
                                    _searchResults.value = immutableResults
                                }
                            }
                    } catch (_: CancellationException) {
                        // Superseded by a newer query; ignore.
                    } catch (e: Exception) {
                        if (request.requestId == latestSearchRequestId.get()) {
                            Timber.e(e, "Error performing search for query: $normalizedQuery")
                            _searchResults.value = persistentListOf()
                        }
                    }
                }
        }
    }

    fun updateSearchFilter(filterType: SearchFilterType) {
        _selectedSearchFilter.value = filterType
    }

    fun loadSearchHistory(limit: Int = 15) {
        scope?.launch {
            try {
                val history = withContext(Dispatchers.IO) {
                    musicRepository.getRecentSearchHistory(limit)
                }
                _searchHistory.value = history.toImmutableList()
            } catch (e: Exception) {
                Timber.e(e, "Error loading search history")
            }
        }
    }

    fun onSearchQuerySubmitted(query: String) {
        scope?.launch {
            if (query.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) {
                        musicRepository.addSearchHistoryItem(query)
                    }
                    loadSearchHistory()
                } catch (e: Exception) {
                    Timber.e(e, "Error adding search history item")
                }
            }
        }
    }

    fun performSearch(query: String) {
        val normalizedQuery = query.trim()

        val requestId = latestSearchRequestId.incrementAndGet()

        if (normalizedQuery.isBlank()) {
            if (_searchResults.value.isNotEmpty()) {
                _searchResults.value = persistentListOf()
            }
        }

        searchRequests.tryEmit(SearchRequest(normalizedQuery, requestId))
    }

    fun deleteSearchHistoryItem(query: String) {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.deleteSearchHistoryItemByQuery(query)
                }
                loadSearchHistory()
            } catch (e: Exception) {
                Timber.e(e, "Error deleting search history item")
            }
        }
    }

    fun clearSearchHistory() {
        scope?.launch {
            try {
                withContext(Dispatchers.IO) {
                    musicRepository.clearSearchHistory()
                }
                _searchHistory.value = persistentListOf()
            } catch (e: Exception) {
                Timber.e(e, "Error clearing search history")
            }
        }
    }

    fun onCleared() {
        searchJob?.cancel()
        scope = null
    }
}
