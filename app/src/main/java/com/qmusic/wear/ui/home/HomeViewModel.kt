package com.qmusic.wear.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 主页 UI 状态 */
data class HomeUiState(
    val loading: Boolean = true,
    val songs: List<Song> = emptyList(),
)

class HomeViewModel : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    init {
        // 登录态变化时自动重载（登录/退出后推荐内容跟随切换）
        viewModelScope.launch {
            ServiceLocator.credential.collect { load() }
        }
    }

    /** 加载首页推荐（登录用猜你喜欢，未登录用推荐新歌） */
    fun load() {
        viewModelScope.launch {
            _ui.value = HomeUiState(loading = true)
            val songs = runCatching {
                ServiceLocator.repository.recommendSongs(ServiceLocator.credential.value)
            }.getOrDefault(emptyList())
            _ui.value = HomeUiState(loading = false, songs = songs)
        }
    }

    fun playFirst() {
        val songs = _ui.value.songs
        if (songs.isNotEmpty()) playFrom(songs, songs.first().mid)
    }

    /** 整列表进队列，从 mid 对应那首开始播 */
    fun playFrom(songs: List<Song>, mid: String) {
        ServiceLocator.player.playFromList(songs, mid)
    }
}
