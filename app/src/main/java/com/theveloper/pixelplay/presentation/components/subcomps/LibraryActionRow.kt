package com.theveloper.pixelplay.presentation.components.subcomps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Dataset
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import androidx.compose.ui.res.stringResource

val defaultShape = RoundedCornerShape(26.dp) // Fallback shape
val genHeight = 42.dp

@Composable
fun LibraryActionRow(
    onMainActionClick: () -> Unit,
    iconRotation: Float,
    onSortClick: () -> Unit,
    onLocateClick: () -> Unit = {},
    showSortButton: Boolean,
    showLocateButton: Boolean = false,
    showImportButton: Boolean = true,
    isPlaylistTab: Boolean,
    onImportM3uClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    isShuffleEnabled: Boolean = false,
    // Storage Filter
    showStorageFilterButton: Boolean = false,
    currentStorageFilter: com.theveloper.pixelplay.data.model.StorageFilter = com.theveloper.pixelplay.data.model.StorageFilter.ALL,
    onStorageFilterClick: () -> Unit = {}
) {
    val shouldShowImport = isPlaylistTab && showImportButton

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        horizontalArrangement = Arrangement.Absolute.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            val outerCorner = 26.dp
            val innerCorner = 8.dp

            val newButtonEndCorner by animateDpAsState(
                targetValue = if (shouldShowImport) innerCorner else outerCorner,
                label = "NewButtonEndCorner"
            )

            val importButtonStartCorner by animateDpAsState(
                targetValue = innerCorner,
                label = "ImportButtonStartCorner"
            )
            // Determine button colors based on shuffle state (not for playlist tab)
            val buttonContainerColor = MaterialTheme.colorScheme.tertiaryContainer
            val buttonContentColor = MaterialTheme.colorScheme.onTertiaryContainer

            FilledTonalButton(
                onClick = onMainActionClick,
                shape = RoundedCornerShape(
                    topStart = 26.dp, bottomStart = 26.dp,
                    topEnd =  newButtonEndCorner, bottomEnd = newButtonEndCorner
                ),
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonContainerColor,
                    contentColor = buttonContentColor
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                modifier = Modifier.height(genHeight)
            ) {
                val icon = if (isPlaylistTab) Icons.AutoMirrored.Rounded.PlaylistAdd else Icons.Rounded.Shuffle
                val text = if (isPlaylistTab) {
                    stringResource(R.string.library_action_new)
                } else {
                    stringResource(R.string.common_shuffle)
                }
                val contentDesc = if (isPlaylistTab) {
                    stringResource(R.string.library_cd_create_new_playlist)
                } else {
                    stringResource(R.string.common_shuffle_play)
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDesc,
                        modifier = Modifier.size(20.dp).rotate(iconRotation)
                    )
                    Text(
                        modifier = Modifier.animateContentSize(),
                        text = text,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            AnimatedVisibility(
                visible = shouldShowImport,
                enter = fadeIn() + expandHorizontally(
                    expandFrom = Alignment.Start,
                    clip = false, // <— evita el 「corte」 durante la expansión
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                ),
                exit = fadeOut() + shrinkHorizontally(
                    shrinkTowards = Alignment.Start,
                    clip = false, // <— evita el 「corte」 durante la expansión
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                )
            ) {
                Row(modifier = Modifier.height(genHeight), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(modifier = Modifier.width(8.dp))

                    FilledTonalButton(
                        onClick = onImportM3uClick,
                        shape = RoundedCornerShape(
                            topStart = importButtonStartCorner,
                            bottomStart = importButtonStartCorner,
                            topEnd = 26.dp,
                            bottomEnd = 26.dp
                        ),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 6.dp
                        ),
                        contentPadding = PaddingValues(
                            horizontal = 14.dp,
                            vertical = 10.dp
                        ),
                        modifier = Modifier.height(genHeight)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.rounded_upload_file_24),
                                contentDescription = stringResource(R.string.library_cd_import_m3u_playlist),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = stringResource(R.string.common_import),
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (showSortButton) {
            val outerCorner = 26.dp
            
            // Logic for Sort Button (Rightmost)
            val sortStartCorner by animateDpAsState(
                targetValue = if (showLocateButton || showStorageFilterButton) 8.dp else outerCorner,
                label = "SortStartCorner"
            )

            // Logic for Filter Button (Middle or Left if Locate hidden)
            // Filter is visible if showStorageFilterButton is true
            val filterEndCorner = 8.dp // Connected to Sort
            val filterStartCorner by animateDpAsState(
                targetValue = if (showLocateButton) 8.dp else outerCorner,
                label = "FilterStartCorner"
            )
            
            // Logic for Locate Button (Leftmost)
            val locateEndCorner = 8.dp // Connected to Filer or Sort

            // Gaps
            // If Filter is shown, gap is between Filter and Sort? OR if we use connected buttons, gap is 4dp between groups or 0dp between connected?
            // Existing code used 4dp gap and 8dp corner. 
            // "SortButtonsGap" was 4dp if showLocateButton else 0dp.
            // If we want "connected" look (segmented), gap should be small (1dp or 2dp) or 0.
            // But existing code uses `4.dp`.
            
            val gapBetweenLocateAndNext by animateDpAsState(
                targetValue = if (showLocateButton) 4.dp else 0.dp,
                label = "GapLocate"
            )
            val gapBetweenFilterAndSort by animateDpAsState(
                targetValue = if (showStorageFilterButton) 4.dp else 0.dp,
                label = "GapFilter"
            )


            Row(verticalAlignment = Alignment.CenterVertically) {
                // Locate Button
                AnimatedVisibility(
                    visible = showLocateButton,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                    FilledTonalIconButton(
                        onClick = onLocateClick,
                        shape = RoundedCornerShape(
                            topStart = outerCorner,
                            bottomStart = outerCorner,
                            topEnd = locateEndCorner,
                            bottomEnd = locateEndCorner
                        ),
                        modifier = Modifier.size(genHeight)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MyLocation,
                            contentDescription = stringResource(R.string.library_cd_locate_current_song)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.width(gapBetweenLocateAndNext))

                // Storage Filter Button
                AnimatedVisibility(
                    visible = showStorageFilterButton,
                    enter = slideInHorizontally(initialOffsetX = { it / 2 }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it / 2 }) + fadeOut()
                ) {
                     val finalIcon = when(currentStorageFilter) {
                         com.theveloper.pixelplay.data.model.StorageFilter.ALL -> Icons.Rounded.Dataset
                         com.theveloper.pixelplay.data.model.StorageFilter.ONLINE -> Icons.Rounded.Cloud
                         com.theveloper.pixelplay.data.model.StorageFilter.OFFLINE -> Icons.Rounded.PhoneAndroid
                     }
                     val tooltipText = when(currentStorageFilter) {
                         com.theveloper.pixelplay.data.model.StorageFilter.ALL -> stringResource(R.string.library_storage_filter_all_songs)
                         com.theveloper.pixelplay.data.model.StorageFilter.ONLINE -> stringResource(R.string.library_storage_filter_online)
                         com.theveloper.pixelplay.data.model.StorageFilter.OFFLINE -> stringResource(R.string.library_storage_filter_offline)
                     }
                     val tooltipState = rememberTooltipState()

                    @OptIn(ExperimentalMaterial3Api::class)
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                        tooltip = {
                            PlainTooltip {
                                Text(tooltipText)
                            }
                        },
                        state = tooltipState
                    ) {
                        FilledTonalIconButton(
                            onClick = onStorageFilterClick,
                            shape = RoundedCornerShape(
                                topStart = filterStartCorner,
                                bottomStart = filterStartCorner,
                                topEnd = filterEndCorner,
                                bottomEnd = filterEndCorner
                            ),
                            modifier = Modifier.size(genHeight)
                        ) {
                             Icon(
                                imageVector = finalIcon,
                                contentDescription = tooltipText
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(gapBetweenFilterAndSort))

                // Sort Button
                FilledTonalIconButton(
                    onClick = onSortClick,
                    shape = RoundedCornerShape(
                        topStart = sortStartCorner,
                        bottomStart = sortStartCorner,
                        topEnd = outerCorner,
                        bottomEnd = outerCorner
                    ),
                    modifier = Modifier.size(genHeight)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.Sort,
                        contentDescription = stringResource(R.string.library_cd_sort_options),
                    )
                }
            }
        }
    }
}

