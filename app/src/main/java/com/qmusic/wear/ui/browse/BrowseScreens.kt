package com.qmusic.wear.ui.browse

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.AlbumDetail
import com.qmusic.wear.data.model.AlbumItem
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PlaylistHeader
import com.qmusic.wear.ui.components.SongRow
import com.qmusic.wear.ui.components.SquareCover
import com.qmusic.wear.ui.components.qmRotarySnap
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
    /** 「专辑」分区（按需加载） */
    val albums: List<AlbumItem> = emptyList(),
    val albumsLoading: Boolean = false,
    val albumsLoaded: Boolean = false,
    val albumsHasMore: Boolean = false,
)

/** 歌手页：分页拉取歌手热门歌曲 / 专辑列表，支持播放全部 / 全部下载 / 长按收藏 */
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

    /** 按需加载歌手专辑（首次进入「专辑」分区调用） */
    fun loadAlbums() {
        val cur = _ui.value
        if (cur.albumsLoaded || cur.albumsLoading || singerMid.isEmpty()) return
        _ui.value = cur.copy(albumsLoading = true)
        viewModelScope.launch {
            val (albums, hasMore) = ServiceLocator.repository.artistAlbums(singerMid, 0)
            _ui.value = _ui.value.copy(
                albumsLoading = false,
                albumsLoaded = true,
                albums = albums,
                albumsHasMore = hasMore,
            )
        }
    }

    fun loadMoreAlbums() {
        val cur = _ui.value
        if (!cur.albumsHasMore || cur.albumsLoading) return
        _ui.value = cur.copy(albumsLoading = true)
        viewModelScope.launch {
            val (more, hasMore) = ServiceLocator.repository.artistAlbums(singerMid, cur.albums.size)
            _ui.value = _ui.value.copy(
                albumsLoading = false,
                albums = _ui.value.albums + more,
                albumsHasMore = hasMore && more.isNotEmpty(),
            )
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

/** 歌手页：歌曲 / 专辑 两个分区（切到「专辑」时按需加载） */
@Composable
fun ArtistScreen(
    singerMid: String,
    singerName: String,
    onOpenPlayer: () -> Unit = {},
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
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
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(singerMid) { vm.load(singerMid) }
    // 切到「专辑」分区时才请求专辑列表（源不支持时返回空 → 空态）
    LaunchedEffect(tab) { if (tab == 1) vm.loadAlbums() }

    QmScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = contentPadding,
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
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

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ArtistTab("歌曲", ui.songs.size, tab == 0, { tab = 0 }, Modifier.weight(1f))
                    ArtistTab("专辑", ui.albums.size, tab == 1, { tab = 1 }, Modifier.weight(1f))
                }
            }

            if (tab == 0) {
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
            } else {
                if (ui.albumsLoading && ui.albums.isEmpty()) {
                    item { CircularProgressIndicator() }
                } else if (ui.albumsLoaded && ui.albums.isEmpty()) {
                    item {
                        Text(
                            text = staleSourceHint().ifEmpty { "暂无专辑" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    items(ui.albums) { a ->
                        GlassRow(onClick = { onOpenAlbum(a.albumMid, a.name) }) {
                            SquareCover(url = a.picUrl, size = 42.dp, corner = 10.dp)
                            Spacer(Modifier.size(9.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    a.name,
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (a.songCount > 0) "${a.songCount} 首" else "专辑",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    if (ui.albumsHasMore) {
                        item {
                            if (ui.albumsLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp))
                            } else {
                                Text(
                                    text = "加载更多专辑",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { vm.loadMoreAlbums() }
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** 歌手页分区切换片（选中=主题色底，未选中=玻璃底） */
@Composable
private fun ArtistTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 26.dp)
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f)
                },
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            if (count > 0) "$label $count" else label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
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

    QmScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = contentPadding,
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
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
