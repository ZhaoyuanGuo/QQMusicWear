package com.qmusic.wear.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PAGE_SIZE = 100

/** 歌单详情页 UI 状态 */
data class SongListUiState(
    val loading: Boolean = true,
    val playlist: Playlist? = null,
    val songs: List<Song> = emptyList(),
    val error: String = "",
    val loadingMore: Boolean = false,
    /** 还有下一页（上一页返回满页时为 true） */
    val hasMore: Boolean = false,
)

class SongListViewModel : ViewModel() {

    private val _ui = MutableStateFlow(SongListUiState())
    val ui: StateFlow<SongListUiState> = _ui.asStateFlow()

    private var disstid: Long = 0L
    private var page = 1

    fun load(id: Long) {
        disstid = id
        page = 1
        viewModelScope.launch {
            _ui.value = SongListUiState(loading = true)
            runCatching {
                ServiceLocator.repository.playlistPage(disstid, page)
            }.onSuccess { (playlist, songs) ->
                _ui.value = SongListUiState(
                    loading = false,
                    playlist = playlist,
                    songs = songs,
                    hasMore = songs.size >= PAGE_SIZE,
                )
            }.onFailure { t ->
                _ui.value = SongListUiState(loading = false, error = t.message ?: "加载失败")
            }
        }
    }

    /** 加载下一页（大歌单超过 100 首时） */
    fun loadMore() {
        val cur = _ui.value
        if (!cur.hasMore || cur.loadingMore || disstid == 0L) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingMore = true)
            runCatching {
                ServiceLocator.repository.playlistPage(disstid, page + 1)
            }.onSuccess { (_, songs) ->
                page += 1
                _ui.value = _ui.value.copy(
                    loadingMore = false,
                    songs = _ui.value.songs + songs,
                    // 返回空页/不满页说明到底了
                    hasMore = songs.size >= PAGE_SIZE,
                )
            }.onFailure {
                _ui.value = _ui.value.copy(loadingMore = false, hasMore = false)
            }
        }
    }

    fun playFrom(mid: String) {
        ServiceLocator.player.playFromList(_ui.value.songs, mid)
    }

    /** 歌单「下载全部」：串行队列，已下载自动跳过，进度见 downloads.batch */
    fun downloadAll() {
        val songs = _ui.value.songs
        if (songs.isEmpty()) return
        ServiceLocator.downloads.enqueueBatch(songs) { song ->
            val quality = ServiceLocator.settingsStore.downloadQuality()
            ServiceLocator.repository.resolveForDownload(song, quality)
        }
    }
}
