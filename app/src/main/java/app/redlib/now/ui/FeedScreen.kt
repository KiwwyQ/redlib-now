package app.redlib.now.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.redlib.now.data.Repo
import app.redlib.now.model.Post
import kotlinx.coroutines.launch

/**
 * Main feed: compact top bar (drawer, sort label, search, refresh) and the
 * post card list with infinite scroll via Redlib after= cursors.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    state: FeedUiState,
    currentFeed: String,
    feedSort: String,
    feedTime: String,
    positionKey: String,
    onSort: (String, String) -> Unit,
    onOpenSearch: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenPost: (Post) -> Unit,
    onOpenComments: (Post) -> Unit,
    onOpenMedia: (Post) -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenGallery: (Post) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSaved: () -> Unit = {},
    onOpenBrowse: () -> Unit = {},
    onOpenPostSearch: (String?) -> Unit = {},
    onOpenFeed: (String) -> Unit,
    onMarkRead: (String) -> Unit = {},
    statePositions: MutableMap<String, Pair<Int, Int>> = mutableMapOf(),
    onExitApp: () -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackHost = remember { SnackbarHostState() }
    var lastBackAt by remember { mutableStateOf(0L) }

    // System back: close drawer first, else double-press to exit on main feed.
    BackHandler {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            val now = System.currentTimeMillis()
            if (now - lastBackAt < 2000L) {
                onExitApp()
            } else {
                lastBackAt = now
                scope.launch {
                    snackHost.showSnackbar("Press back again to exit")
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "Now for Redlib",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(20.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Menu, contentDescription = null) },
                    label = { Text("Frontpage") },
                    selected = currentFeed == "/",
                    onClick = {
                        onOpenFeed("/")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Popular") },
                    selected = currentFeed == "/r/popular",
                    onClick = {
                        onOpenFeed("/r/popular")
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                if (Repo.pinnedState.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        "Pinned",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
                    )
                    Repo.pinnedState.forEach { sub ->
                        NavigationDrawerItem(
                            label = { Text("r/$sub") },
                            selected = currentFeed == "/r/$sub",
                            onClick = {
                                onOpenFeed("/r/$sub")
                                scope.launch { drawerState.close() }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.BookmarkBorder, contentDescription = null) },
                    label = { Text("Saved") },
                    selected = false,
                    onClick = {
                        onOpenSaved()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Search posts") },
                    selected = false,
                    onClick = {
                        val sub = if (currentFeed.startsWith("/r/"))
                            currentFeed.removePrefix("/r/").removeSuffix("/").ifBlank { null }
                        else null
                        onOpenPostSearch(sub)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.GridOn, contentDescription = null) },
                    label = { Text("Browse subreddits") },
                    selected = false,
                    onClick = {
                        onOpenBrowse()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = false,
                    onClick = {
                        onOpenSettings()
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    state.instanceStatus,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    },
                    title = {
                        Text(
                            when {
                                currentFeed == "/" -> "Frontpage"
                                currentFeed.startsWith("/r/") ->
                                    "r/" + currentFeed.removePrefix("/r/").removeSuffix("/")
                                else -> "Now for Redlib"
                            },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        val subName = remember(currentFeed) {
                            currentFeed
                                .removePrefix("/r/")
                                .removeSuffix("/")
                                .takeIf {
                                    currentFeed.startsWith("/r/") &&
                                        it.isNotBlank() &&
                                        it !in listOf("all", "popular")
                                }
                        }
                        if (subName != null) {
                            val isPinned = Repo.isPinned(subName)
                            IconButton(
                                onClick = {
                                    if (isPinned) Repo.unpin(subName) else Repo.pin(subName)
                                },
                            ) {
                                Icon(
                                    if (isPinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = if (isPinned) "Unpin r/$subName" else "Pin r/$subName",
                                    tint = if (isPinned) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        var sortMenuOpen by remember { mutableStateOf(false) }
                        TextButton(onClick = { sortMenuOpen = true }) {
                            Text(
                                sortLabel(feedSort, feedTime),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Sort",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                                listOf("hot" to "Hot", "new" to "New", "rising" to "Rising").forEach { (id, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { onSort(id, "all"); sortMenuOpen = false },
                                    )
                                }
                                HorizontalDivider()
                                listOf("top" to "Top", "controversial" to "Controversial").forEach { (id, label) ->
                                    listOf(
                                        "hour" to "Past hour", "day" to "Today", "week" to "This week",
                                        "month" to "This month", "year" to "This year", "all" to "All time",
                                    ).forEach { (t, tl) ->
                                        DropdownMenuItem(
                                            text = { Text("$label · $tl") },
                                            onClick = { onSort(id, t); sortMenuOpen = false },
                                        )
                                    }
                                }
                            }
                        }
                        // In a subreddit → post search scoped to that sub; else subreddit finder.
                        IconButton(onClick = {
                            if (subName != null) onOpenPostSearch(subName)
                            else onOpenSearch()
                        }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = if (subName != null) "Search posts in r/$subName" else "Search subreddits",
                            )
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    },
                )
            },
            snackbarHost = {
                SnackbarHost(snackHost)
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                // Visible refresh feedback while content is still on screen.
                if (state.loading && state.posts.isNotEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                when {
                    state.loading && state.posts.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            if (state.instanceStatus.isNotBlank()) {
                                Text(
                                    state.instanceStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                            }
                        }
                    }
                    state.posts.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (state.error != null) state.error else "Nothing to show yet.") }
                    else -> {
                        val remembered = if (app.redlib.now.data.Settings.rememberSubredditPosition)
                            statePositions[positionKey] else null
                        val listState = remember(positionKey) {
                            androidx.compose.foundation.lazy.LazyListState(
                                firstVisibleItemIndex = remembered?.first ?: 0,
                                firstVisibleItemScrollOffset = remembered?.second ?: 0,
                            )
                        }
                        // Persist scroll while browsing; flush on leave.
                        LaunchedEffect(listState, positionKey) {
                            snapshotFlow {
                                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                            }.collect { statePositions[positionKey] = it }
                        }
                        DisposableEffect(positionKey) {
                            onDispose {
                                statePositions[positionKey] =
                                    listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
                            }
                        }
                        // When sort/path changes and we intentionally cleared position, jump to top.
                        LaunchedEffect(positionKey, state.posts.firstOrNull()?.id) {
                            if (remembered == null && listState.firstVisibleItemIndex > 0) {
                                listState.scrollToItem(0)
                            }
                        }
                        // Infinite scroll: request next page near the end.
                        val shouldLoadMore by remember {
                            derivedStateOf {
                                val info = listState.layoutInfo
                                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                val total = info.totalItemsCount
                                total > 0 && last >= total - 3
                            }
                        }
                        LaunchedEffect(shouldLoadMore, state.after, state.loadingMore, state.endReached) {
                            if (shouldLoadMore && state.after != null && !state.loadingMore && !state.endReached && !state.loading) {
                                onLoadMore()
                            }
                        }
                        LaunchedEffect(state.error) {
                            state.error?.let { snackHost.showSnackbar(it) }
                        }

                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(state.posts, key = { it.id }) { post ->
                                PostCard(
                                    post = post,
                                    onClick = { onOpenPost(post) },
                                    onOpenComments = { onMarkRead(post.id); onOpenComments(post) },
                                    onOpenMedia = { onOpenMedia(post) },
                                    onOpenUser = onOpenUser,
                                    onOpenSubreddit = { onOpenFeed("/r/$it") },
                                    onOpenGallery = { onOpenGallery(post) },
                                )
                            }
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    when {
                                        state.loadingMore -> CircularProgressIndicator(Modifier.size(28.dp))
                                        state.endReached -> Text(
                                            "That's all",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        else -> Spacer(Modifier.height(8.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class FeedUiState(
    val loading: Boolean = false,
    val posts: List<Post> = emptyList(),
    val error: String? = null,
    val instanceStatus: String = "",
    val after: String? = null,
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
)

private fun sortLabel(sort: String, time: String): String = when (sort) {
    "hot" -> "Hot"
    "new" -> "New"
    "rising" -> "Rising"
    else -> {
        val base = if (sort == "top") "Top" else "Controversial"
        base + when (time) {
            "hour" -> " · Hour"
            "day" -> " · Today"
            "week" -> " · Week"
            "month" -> " · Month"
            "year" -> " · Year"
            else -> " · All"
        }
    }
}
