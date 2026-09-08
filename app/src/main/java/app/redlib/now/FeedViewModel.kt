package app.redlib.now

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.redlib.now.data.FeedCache
import app.redlib.now.data.MediaCache
import app.redlib.now.data.Repo
import app.redlib.now.data.Settings
import app.redlib.now.model.Post
import app.redlib.now.parse.PostParser
import app.redlib.now.ui.FeedUiState
import kotlinx.coroutines.launch

class FeedViewModel : ViewModel() {

    private val client = Repo.client

    var state by mutableStateOf(FeedUiState())
        private set

    var currentPath by mutableStateOf("/")
        private set

    // Sort state (parity with the classic app's sort menus).
    var feedSort by mutableStateOf("hot")      // hot, new, rising, top, controversial
        private set
    var feedTime by mutableStateOf("all")      // hour, day, week, month, year, all
        private set

    private val loadedPaths = mutableSetOf<String>()
    private var fetchJob: kotlinx.coroutines.Job? = null
    private var loadMoreJob: kotlinx.coroutines.Job? = null

    /** Per-feed scroll positions keyed by path|sort|time. */
    val positions = mutableMapOf<String, Pair<Int, Int>>()

    init {
        load("/", initial = true)
    }

    fun positionKey(path: String = currentPath, sort: String = feedSort, time: String = feedTime): String =
        "$path|$sort|$time"

    fun setSort(sort: String, time: String = feedTime) {
        feedSort = sort
        feedTime = time
        // New listing — drop saved offset so UI starts at top.
        positions.remove(positionKey())
        load(currentPath)
    }

    /** Full fetch path for the current feed + sort (no after=). */
    private fun fetchPath(base: String): String {
        val sort = if (feedSort == "hot") "" else "/$feedSort"
        val time = if (feedSort == "top" || feedSort == "controversial") "?t=$feedTime" else ""
        val joined = if (base == "/") "/${feedSort}" else "$base$sort"
        return when {
            feedSort == "hot" -> base
            base == "/" -> joined + time
            else -> base + sort + time
        }
    }

    /** Append after= cursor, preserving existing query string. */
    private fun pathWithAfter(basePath: String, after: String): String {
        val sep = if (basePath.contains('?')) '&' else '?'
        return basePath + sep + "after=" + java.net.URLEncoder.encode(after, "UTF-8")
    }

    fun load(path: String, initial: Boolean = false) {
        currentPath = path
        loadMoreJob?.cancel()

        val cached = FeedCache.loadFeed(path)
        if (cached != null) {
            state = FeedUiState(
                loading = true,
                posts = cached.posts,
                instanceStatus = "cached ${MediaCache.ageString(cached.savedAt)} — refreshing…",
                after = null,
                loadingMore = false,
                endReached = false,
            )
        } else {
            state = FeedUiState(loading = true)
        }

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            try {
                val response = client.fetch(fetchPath(path))
                val page = PostParser.parseFeedPage(response.html, response.baseUrl)
                val posts = page.posts
                    .filter { Settings.postVisible(it) }
                    .filter { !Settings.hideReadPosts || !Repo.isRead(it.id) }
                loadedPaths += path
                state = FeedUiState(
                    loading = false,
                    posts = posts,
                    instanceStatus = "served by ${response.baseUrl.removePrefix("https://")}",
                    after = page.after,
                    loadingMore = false,
                    endReached = page.after == null,
                )
                FeedCache.saveFeed(path, posts)
                if (cached == null) MediaCache.prefetch(posts)
            } catch (e: Exception) {
                if (cached != null) {
                    state = state.copy(loading = false, error = null)
                } else {
                    state = state.copy(
                        loading = false,
                        posts = if (initial) emptyList() else state.posts,
                        error = "Failed to load: ${e.message ?: e.javaClass.simpleName}",
                    )
                }
            }
        }
    }

    fun refresh() = load(currentPath)

    /** Load next page using Reddit/Redlib after= cursor (last post id token). */
    fun loadMore() {
        val after = state.after ?: return
        if (state.loading || state.loadingMore || state.endReached) return
        if (loadMoreJob?.isActive == true) return

        loadMoreJob = viewModelScope.launch {
            state = state.copy(loadingMore = true, error = null)
            try {
                val base = fetchPath(currentPath)
                val response = client.fetch(pathWithAfter(base, after))
                val page = PostParser.parseFeedPage(response.html, response.baseUrl)
                val more = page.posts
                    .filter { Settings.postVisible(it) }
                    .filter { !Settings.hideReadPosts || !Repo.isRead(it.id) }
                // Dedupe by id in case of overlap.
                val existing = state.posts.map { it.id }.toHashSet()
                val merged = state.posts + more.filter { it.id !in existing }
                state = state.copy(
                    loadingMore = false,
                    posts = merged,
                    after = page.after,
                    endReached = page.after == null || more.isEmpty(),
                    instanceStatus = "served by ${response.baseUrl.removePrefix("https://")}",
                )
                FeedCache.saveFeed(currentPath, merged)
            } catch (e: Exception) {
                state = state.copy(
                    loadingMore = false,
                    error = "Failed to load more: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    fun markRead(id: String) {
        Repo.markRead(id)
        if (Settings.hideReadPosts) {
            state = state.copy(posts = state.posts.filter { it.id != id })
        }
    }
}
