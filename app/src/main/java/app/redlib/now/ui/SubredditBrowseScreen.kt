package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo

/** Subreddit browser: classic list-vs-grid styles over suggestions + history. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SubredditBrowseScreen(
    onBack: () -> Unit,
    onOpenSubreddit: (String) -> Unit,
) {
    BackHandler(onBack = onBack)
    var grid by remember { mutableStateOf(true) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    val subs = remember(Repo.historyState) { (Repo.historyState + Repo.SUGGESTIONS).distinct() }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            title = { Text("Browse subreddits", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.Filled.List else Icons.Filled.GridOn, contentDescription = "Toggle layout")
                }
            },
        )
        statusMsg?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (grid) {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 110.dp), contentPadding = PaddingValues(8.dp)) {
                items(subs, key = { it }) { sub ->
                    val isPinned = sub in Repo.historyState
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .padding(5.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(subredditColor(sub).copy(alpha = 0.18f))
                            .combinedClickable(
                                onClick = { onOpenSubreddit(sub) },
                                // Bug #6: explicit long-press to pin/unpin to history.
                                onLongClick = {
                                    if (isPinned) {
                                        Repo.remove(sub)
                                        statusMsg = "Removed r/$sub from history"
                                    } else {
                                        Repo.add(sub)
                                        statusMsg = "Pinned r/$sub to history"
                                    }
                                },
                            )
                            .padding(vertical = 22.dp, horizontal = 6.dp),
                    ) {
                        Text(
                            "r/$sub",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = subredditColor(sub),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isPinned) {
                            Icon(
                                Icons.Filled.BookmarkRemove,
                                contentDescription = "Pinned (long-press to unpin)",
                                tint = subredditColor(sub),
                                modifier = Modifier.padding(top = 4.dp).size(14.dp),
                            )
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 260.dp), contentPadding = PaddingValues(8.dp)) {
                items(subs, key = { it }) { sub ->
                    val isPinned = sub in Repo.historyState
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(5.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(subredditColor(sub).copy(alpha = 0.12f))
                            .combinedClickable(
                                onClick = { onOpenSubreddit(sub) },
                                onLongClick = {
                                    if (isPinned) {
                                        Repo.remove(sub)
                                        statusMsg = "Removed r/$sub from history"
                                    } else {
                                        Repo.add(sub)
                                        statusMsg = "Pinned r/$sub to history"
                                    }
                                },
                            )
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    ) {
                        Text(
                            "r/$sub",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = subredditColor(sub),
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                        if (isPinned) {
                            Icon(
                                Icons.Filled.BookmarkRemove,
                                contentDescription = "Pinned (long-press to unpin)",
                                tint = subredditColor(sub),
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.BookmarkAdd,
                                contentDescription = "Long-press to pin to history",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    }
}
