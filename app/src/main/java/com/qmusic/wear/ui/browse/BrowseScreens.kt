package com.qmusic.wear.ui.browse

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.AlbumDetail
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.ui.components.PlaylistHeader
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.rotaryList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val ARTIST_PAGE_SIZE = 30

/** 音乐源插件是否具备歌手/专辑能力（v7 起新增 artistSongs/albumSongs/lyricRoma，v8 起补备用通道） */
internal fun staleSourceHint(): String =
    if (com.qmusic.wear.data.source.SourceManager.currentVersion() < 9) {
        "音乐源版本过旧，请到「设置 → 更新音乐源」升级后重试"
    } else {
        ""
    }

/** 歌手页 UI 状态 */
data class ArtistUiState(
    val loading: Boolean = true,
    val songs: List<Song> = emptyList(),
    val error: String = "",
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
)

/** 歌手页：分页拉取歌手热门歌曲，支持播放全部 / 全部下载 / 长按收藏 */
class ArtistViewModel : ViewModel() {

    private val _ui = MutableStateFlow(ArtistUiState())
    val ui: StateFlow<ArtistUiState> = _ui.asStateFlow()

    private var singerMid: String = ""
    private var page = 1

    fun load(mid: String) {
        if (singerMid == mid && _ui.value.songs.isNotEmpty()) return
        singerMid = mid
        page = 1
        viewModelScope.launch {
            _ui.value = ArtistUiState(loading = true)
            val (songs, hasMore) = ServiceLocator.repository.artistSongs(mid, page)
            _ui.value = if (songs.isEmpty()) {
                ArtistUiState(loading = false, error = "加载失败或暂无歌曲")
            } else {
                ArtistUiState(loading = false, songs = songs, hasMore = hasMore)
            }
        }
    }

    fun loadMore() {
        val cur = _ui.value
        if (!cur.hasMore || cur.loadingMore || singerMid.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingMore = true)
            val (songs, hasMore) = ServiceLocator.repository.artistSongs(singerMid, page + 1)
            page += 1
            _ui.value = _ui.value.copy(
                loadingMore = false,
                songs = _ui.value.songs + songs,
                hasMore = hasMore && songs.isNotEmpty(),
            )
        }
    }

    fun playFrom(mid: String) {
        ServiceLocator.player.playFromList(_ui.value.songs, mid)
    }

    fun downloadAll() {
        val songs = _ui.value.songs
        if (songs.isEmpty()) return
        ServiceLocator.downloads.enqueueBatch(songs) { song ->
            val quality = ServiceLocator.settingsStore.downloadQuality()
            ServiceLocator.repository.resolveForDownload(song, quality)
        }
    }
}

/** 专辑页 UI 状态 */
data class AlbumUiState(
    val loading: Boolean = true,
    val album: AlbumDetail? = null,
    val error: String = "",
)

/** 专辑页：整张专辑曲目，支持播放全部 / 全部下载 / 长按收藏 */
class AlbumViewModel : ViewModel() {

    private val _ui = MutableStateFlow(AlbumUiState())
    val ui: StateFlow<AlbumUiState> = _ui.asStateFlow()

    private var albumMid: String = ""

    fun load(mid: String) {
        if (albumMid == mid && _ui.value.album != null) return
        albumMid = mid
        viewModelScope.launch {
            _ui.value = AlbumUiState(loading = true)
            val album = ServiceLocator.repository.albumSongs(mid)
            _ui.value = if (album == null || album.songs.isEmpty()) {
                AlbumUiState(loading = false, error = "加载失败或专辑为空")
            } else {
                AlbumUiState(loading = false, album = album)
            }
        }
    }

    fun playFrom(mid: String) {
        val songs = _ui.value.album?.songs ?: return
        ServiceLocator.player.playFromList(songs, mid)
    }

    fun downloadAll() {
        val songs = _ui.value.album?.songs ?: return
        if (songs.isEmpty()) return
        ServiceLocator.downloads.enqueueBatch(songs) { song ->
            val quality = ServiceLocator.settingsStore.downloadQuality()
            ServiceLocator.repository.resolveForDownload(song, quality)
        }
    }
}

/** 歌手页：按热门分页列出歌手歌曲 */
@Composable
fun ArtistScreen(
    singerMid: String,
    singerName: String,
    onOpenPlayer: () -> Unit = {},
    vm: ArtistViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val batch by ServiceLocator.downloads.batch.collectAsStateWithLifecycle()
    val downloadedSet by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    LaunchedEffect(singerMid) { vm.load(singerMid) }

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().rotaryList(listState),
        ) {
            item {
                PlaylistHeader(
                    title = singerName,
                    coverUrl = "",
                    songCount = ui.songs.size,
                    onPlayAll = if (ui.songs.isEmpty()) null else {
                        { vm.playFrom(ui.songs.first().mid); onOpenPlayer() }
                    },
                    onDownloadAll = if (ui.songs.isEmpty()) null else { { vm.downloadAll() } },
                    downloadPending = batch.running && batch.total > 0,
                )
            }

            if (ui.loading && ui.songs.isEmpty()) {
                item { CircularProgressIndicator() }
            } else if (ui.songs.isEmpty()) {
                item {
                    Text(
                        text = staleSourceHint().ifEmpty { ui.error },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(ui.songs) { song ->
                    SongRow(
                        song = song,
                        playing = now.song?.mid == song.mid,
                        liked = likedMids.contains(song.mid),
                        downloaded = downloadedSet.any { it.song.mid == song.mid },
                        onClick = {
                            ServiceLocator.player.enqueueAndPlay(song)
                            onOpenPlayer()
                        },
                        onLongClick = {
                            scope.launch {
                                val like = !likedMids.contains(song.mid)
                                val ok = ServiceLocator.repository.setLiked(song, like)
                                Toast.makeText(
                                    ctx,
                                    if (!ok) "收藏失败（需登录）" else if (like) "已加入我喜欢" else "已取消喜欢",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }

                if (ui.hasMore) {
                    item {
                        if (ui.loadingMore) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp))
                        } else {
                            Text(
                                text = "加载更多（已载 ${ui.songs.size} 首）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { vm.loadMore() }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** 专辑页：专辑封面 + 曲目列表 */
@Composable
fun AlbumScreen(
    albumMid: String,
    onOpenPlayer: () -> Unit = {},
    vm: AlbumViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()
    val batch by ServiceLocator.downloads.batch.collectAsStateWithLifecycle()
    val downloadedSet by ServiceLocator.downloads.downloadsFlow.collectAsStateWithLifecycle()
    val likedMids by ServiceLocator.repository.likedMids.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val album = ui.album

    LaunchedEffect(albumMid) { vm.load(albumMid) }

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().rotaryList(listState),
        ) {
            if (album != null) {
                item {
                    PlaylistHeader(
                        title = album.name,
                        coverUrl = album.coverUrl,
                        songCount = album.songs.size,
                        onPlayAll = { vm.playFrom(album.songs.first().mid); onOpenPlayer() },
                        onDownloadAll = { vm.downloadAll() },
                        downloadPending = batch.running && batch.total > 0,
                    )
                }
                items(album.songs) { song ->
                    SongRow(
                        song = song,
                        playing = now.song?.mid == song.mid,
                        liked = likedMids.contains(song.mid),
                        downloaded = downloadedSet.any { it.song.mid == song.mid },
                        onClick = {
                            ServiceLocator.player.enqueueAndPlay(song)
                            onOpenPlayer()
                        },
                        onLongClick = {
                            scope.launch {
                                val like = !likedMids.contains(song.mid)
                                val ok = ServiceLocator.repository.setLiked(song, like)
                                Toast.makeText(
                                    ctx,
                                    if (!ok) "收藏失败（需登录）" else if (like) "已加入我喜欢" else "已取消喜欢",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            } else {
                item {
                    if (ui.loading) {
                        CircularProgressIndicator()
                    } else {
                        Text(
                            text = staleSourceHint().ifEmpty { ui.error },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
