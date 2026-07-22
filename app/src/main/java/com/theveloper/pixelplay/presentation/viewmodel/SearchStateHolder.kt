package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.model.Album
import com.theveloper.pixelplay.data.model.Artist
import com.theveloper.pixelplay.data.model.SearchFilterType
import com.theveloper.pixelplay.data.model.SearchHistoryItem
import com.theveloper.pixelplay.data.model.SearchResultItem
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.search.SearchRanker
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
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

    /** Live gateway search results (on-demand YouTube songs + artist + album results). */
    private data class LiveResults(
        val songs: List<Song> = emptyList(),
        val artists: List<Artist> = emptyList(),
        val albums: List<Album> = emptyList(),
    ) {
        val isEmpty: Boolean get() = songs.isEmpty() && artists.isEmpty() && albums.isEmpty()
    }

    /**
     * Merge live gateway results into the local results, de-duped, honouring the active filter
     * (songs for ALL/SONGS, artists for ALL/ARTISTS, albums for ALL/ALBUMS). This is what makes
     * search surface artists and full albums, not just songs.
     */
    private fun mergeLive(
        local: List<SearchResultItem>,
        live: LiveResults,
        filter: SearchFilterType
    ): List<SearchResultItem> {
        if (live.isEmpty) return local
        var out = local
        if ((filter == SearchFilterType.ALL || filter == SearchFilterType.SONGS) && live.songs.isNotEmpty()) {
            // De-dup on the GATEWAY id: a synced library row carries id="<unified Long>" while the
            // same track from live search carries id="navidrome_<gatewayId>". Those id spaces are
            // disjoint, so comparing Song.id would never match and every synced track would double up.
            val seen = out.mapNotNull { (it as? SearchResultItem.SongItem)?.song }
                .map { it.navidromeId ?: it.id }
                .toHashSet()
            out = out + live.songs
                .filterNot { (it.navidromeId ?: it.id) in seen }
                .map { SearchResultItem.SongItem(it) }
        }
        if ((filter == SearchFilterType.ALL || filter == SearchFilterType.ARTISTS) && live.artists.isNotEmpty()) {
            val seen = out.mapNotNull { (it as? SearchResultItem.ArtistItem)?.artist?.name?.lowercase() }.toHashSet()
            out = out + live.artists.filterNot { it.name.lowercase() in seen }.map { SearchResultItem.ArtistItem(it) }
        }
        if ((filter == SearchFilterType.ALL || filter == SearchFilterType.ALBUMS) && live.albums.isNotEmpty()) {
            val seen = out.mapNotNull { r ->
                (r as? SearchResultItem.AlbumItem)?.album?.let { "${it.title.lowercase()}|${it.artist.lowercase()}" }
            }.toHashSet()
            out = out + live.albums
                .filterNot { "${it.title.lowercase()}|${it.artist.lowercase()}" in seen }
                .map { SearchResultItem.AlbumItem(it) }
        }
        return out
    }

    /**
     * Position of each result within the list it came from, keyed the way [SearchRanker] keys
     * items. The gateway returns songs/artists/albums in popularity order, but the merge
     * concatenates the three lists, so the merged index destroys that signal — this preserves it.
     * Local results are ranked among themselves; their relevance comes from play history instead.
     */
    private fun buildSourceRanks(local: List<SearchResultItem>, live: LiveResults): Map<String, Int> {
        val ranks = HashMap<String, Int>()
        // Local first, so a gateway hit for the same entity overwrites with its upstream position.
        local.forEachIndexed { i, item -> ranks[SearchRanker.itemKey(item)] = i }
        live.songs.forEachIndexed { i, s ->
            ranks[SearchRanker.itemKey(SearchResultItem.SongItem(s))] = i
        }
        live.artists.forEachIndexed { i, a ->
            ranks[SearchRanker.itemKey(SearchResultItem.ArtistItem(a))] = i
        }
        live.albums.forEachIndexed { i, al ->
            ranks[SearchRanker.itemKey(SearchResultItem.AlbumItem(al))] = i
        }
        return ranks
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

    /** True while the live gateway (on-demand YouTube) search is in flight. */
    private val _isLiveSearching = MutableStateFlow(false)
    val isLiveSearching = _isLiveSearching.asStateFlow()

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
                        val liveFlow = flow {
                            emit(LiveResults())
                            _isLiveSearching.value = true
                            try {
                                val live = coroutineScope {
                                    val songs = async {
                                        runCatching {
                                            navidromeRepository.searchSongs(normalizedQuery, limit = 40)
                                                .getOrDefault(emptyList())
                                        }.getOrDefault(emptyList())
                                    }
                                    val artists = async {
                                        runCatching {
                                            navidromeRepository.searchArtists(normalizedQuery, limit = 10)
                                                .getOrDefault(emptyList())
                                        }.getOrDefault(emptyList())
                                    }
                                    val albums = async {
                                        runCatching {
                                            navidromeRepository.searchAlbums(normalizedQuery, limit = 20)
                                                .getOrDefault(emptyList())
                                        }.getOrDefault(emptyList())
                                    }
                                    LiveResults(songs.await(), artists.await(), albums.await())
                                }
                                if (!live.isEmpty) emit(live)
                            } finally {
                                _isLiveSearching.value = false
                            }
                        }

                        musicRepository.searchAll(normalizedQuery, currentFilter)
                            // combine() produces nothing until BOTH sides have emitted. On a
                            // streaming-only library the local flow can be slow or emit nothing,
                            // which stalled the whole pipeline and showed no results even though
                            // the gateway had already answered. Seed it so live results alone can
                            // render.
                            .onStart { emit(emptyList<SearchResultItem>()) }
                            .combine(liveFlow) { local, live -> local to live }
                            .collect { (resultsList, liveResults) ->
                                val merged = mergeLive(resultsList, liveResults, _selectedSearchFilter.value)

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
                                val ranked = SearchRanker.rank(
                                    normalizedQuery,
                                    merged,
                                    playStats,
                                    buildSourceRanks(resultsList, liveResults)
                                )

                                // NOTE: deliberately no `requestId != latest` guard here.
                                // collectLatest already cancels this block the moment a newer
                                // request arrives, so the guard was redundant — and harmful:
                                // performSearch() bumps the counter on every call, including
                                // blank-query calls that return early without ever producing
                                // results. That left the counter ahead of this in-flight request,
                                // so a perfectly good result set for the *current* query was
                                // thrown away and nothing replaced it — the intermittent
                                // "search shows nothing".
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
