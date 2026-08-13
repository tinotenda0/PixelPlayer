package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.lifecycle.ViewModel
import com.theveloper.pixelplay.data.navidrome.NavidromeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryStateHolder: LibraryStateHolder,
    private val navidromeRepository: NavidromeRepository
) : ViewModel() {

    val songsPagingFlow = libraryStateHolder.songsPagingFlow.cachedIn(viewModelScope)

    val albumsPagingFlow = libraryStateHolder.albumsPagingFlow.cachedIn(viewModelScope)

    val artistsPagingFlow = libraryStateHolder.artistsPagingFlow.cachedIn(viewModelScope)

    val favoritesPagingFlow = libraryStateHolder.favoritesPagingFlow.cachedIn(viewModelScope)

    val favoriteSongCountFlow = libraryStateHolder.favoriteSongCountFlow

    val isLoadingLibrary = libraryStateHolder.isLoadingLibrary

    val likedArtists = navidromeRepository.likedArtists

    init {
        viewModelScope.launch { navidromeRepository.refreshLikedArtists() }
    }
}
