package ovh.battistella.ondes.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ovh.battistella.ondes.R
import ovh.battistella.ondes.data.local.PodcastWithCount
import ovh.battistella.ondes.ui.components.OndesEmptyState
import ovh.battistella.ondes.ui.components.PodcastArtwork
import ovh.battistella.ondes.ui.screens.downloads.DownloadsScreen
import ovh.battistella.ondes.ui.theme.OndesTheme

/** The two views the Library hosts: subscribed shows and offline downloads. */
private enum class LibraryTab(val labelRes: Int) {
    PODCASTS(R.string.library_tab_podcasts),
    DOWNLOADS(R.string.nav_downloads),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenPodcast: (String) -> Unit,
    onOpenPlayer: () -> Unit,
    onBrowse: () -> Unit,
    contentPadding: PaddingValues,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val selected by viewModel.selectedFeedUrls.collectAsStateWithLifecycle()
    // rememberSaveable: the picked tab used to snap back to Podcasts on rotation
    // (issue P2).
    var tab by rememberSaveable { mutableStateOf(LibraryTab.PODCASTS) }
    var showConfirm by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    // Selection mode only applies to the Podcasts tab.
    val selecting = selected.isNotEmpty() && tab == LibraryTab.PODCASTS

    // System back exits selection mode before leaving the screen.
    BackHandler(enabled = selecting) { viewModel.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    title = {
                        Text(pluralStringResource(R.plurals.selected_count, selected.size, selected.size))
                    },
                    actions = {
                        IconButton(onClick = { showConfirm = true }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.unsubscribe_selected),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.nav_library)) },
                    actions = {
                        if (tab == LibraryTab.PODCASTS && subscriptions.isNotEmpty()) {
                            SortMenu(current = sort, onSelect = viewModel::setSort)
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OndesTheme.spacing.lg, vertical = OndesTheme.spacing.sm),
            ) {
                LibraryTab.entries.forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = tab == entry,
                        onClick = {
                            if (entry != tab) viewModel.clearSelection()
                            tab = entry
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, LibraryTab.entries.size),
                    ) {
                        Text(stringResource(entry.labelRes))
                    }
                }
            }

            when (tab) {
                LibraryTab.PODCASTS -> if (subscriptions.isEmpty()) {
                    OndesEmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = stringResource(R.string.library_empty_title),
                        message = stringResource(R.string.library_empty_message),
                        actionLabel = stringResource(R.string.browse_podcasts),
                        onAction = onBrowse,
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = OndesTheme.spacing.lg, end = OndesTheme.spacing.lg,
                            top = OndesTheme.spacing.lg,
                            bottom = OndesTheme.spacing.lg + contentPadding.calculateBottomPadding(),
                        ),
                        horizontalArrangement = Arrangement.spacedBy(OndesTheme.spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(OndesTheme.spacing.lg),
                    ) {
                        items(subscriptions, key = { it.podcast.feedUrl }) { item ->
                            PodcastTile(
                                item = item,
                                selecting = selecting,
                                isSelected = item.podcast.feedUrl in selected,
                                onClick = {
                                    if (selecting) viewModel.toggleSelection(item.podcast.feedUrl)
                                    else onOpenPodcast(item.podcast.feedUrl)
                                },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.startSelection(item.podcast.feedUrl)
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }

                LibraryTab.DOWNLOADS -> DownloadsScreen(
                    onOpenPlayer = onOpenPlayer,
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.unsubscribe_confirm_title)) },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.unsubscribe_confirm_message, selected.size, selected.size,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.unsubscribeSelected()
                    showConfirm = false
                }) {
                    Text(stringResource(R.string.unsubscribe_selected))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PodcastTile(
    item: PodcastWithCount,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unplayedCd = if (item.unplayedCount > 0) {
        pluralStringResource(
            R.plurals.unplayed_count, item.unplayedCount, item.unplayedCount,
        )
    } else ""
    Column(
        modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { selected = isSelected },
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .then(
                    if (isSelected) {
                        Modifier.border(
                            width = 3.dp,
                            color = MaterialTheme.colorScheme.primary,
                            shape = OndesTheme.shapes.artworkMedium,
                        )
                    } else Modifier,
                ),
        ) {
            PodcastArtwork(
                url = item.podcast.imageUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = OndesTheme.shapes.artworkMedium,
            )
            if (isSelected) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(OndesTheme.shapes.artworkMedium)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(OndesTheme.spacing.sm)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                }
            } else if (!selecting && item.unplayedCount > 0) {
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(OndesTheme.spacing.sm)
                        .semantics { contentDescription = unplayedCd },
                ) {
                    Text(if (item.unplayedCount > 99) "99+" else "${item.unplayedCount}")
                }
            }
        }
        Spacer(Modifier.height(OndesTheme.spacing.sm))
        Text(
            text = item.podcast.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = item.podcast.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SortMenu(current: LibrarySort, onSelect: (LibrarySort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val labelFor = mapOf(
        LibrarySort.RECENT to R.string.sort_recent,
        LibrarySort.ALPHABETICAL to R.string.sort_alphabetical,
        LibrarySort.UNPLAYED to R.string.sort_unplayed,
    )
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.AutoMirrored.Rounded.Sort,
                contentDescription = stringResource(R.string.sort_library),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelFor.getValue(option))) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    trailingIcon = if (option == current) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}
