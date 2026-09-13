package com.qmusic.wear.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.model.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 我的页 UI 状态 */
data class MineUiState(
    val profile: UserProfile? = null,
    val likedPlaylist: Playlist? = null,
    val favPlaylists: List<Playlist> = emptyList(),
    /** 已登录但资料/歌单全部拉取失败 → 提示登录可能过期 */
    val suspectSession: Boolean = false,
)

/** 我的页顶部搜索状态 */
data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val result: SearchResult? = null,
)

/**
 * 「我的」页（每日推荐右滑页）ViewModel：
 * 资料与歌单加载 + 顶部搜索（歌曲/歌手/歌单）。
 */
class MineViewModel : ViewModel() {

    private val _ui = MutableStateFlow(MineUiState())
    val ui: StateFlow<MineUiState> = _ui.asStateFlow()

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private var searchJob: Job? = null

    init {
        // 登录态变化时自动重载：登录/退出后无需重建页面即可刷新
        viewModelScope.launch {
            ServiceLocator.credential.collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            val cred = ServiceLocator.credential.value
            if (!cred.isLogged) {
                _ui.value = MineUiState()
                return@launch
            }
            val profile = runCatching {
                ServiceLocator.repository.profile(cred)
            }.getOrNull()
            // 补全 encryptUin（收藏歌单接口必需；旧凭据缺该字段时自动修复，无需重新扫码）
            profile?.encryptUin?.takeIf { it.isNotEmpty() }?.let {
                ServiceLocator.onEncryptUinObtained(it)
            }
            val all = runCatching {
                ServiceLocator.repository.allMyPlaylists()
            }.getOrDefault(emptyList())
            val liked = all.firstOrNull { it.name.contains("我喜欢") }
            val favs = all.filter { it.disstid != liked?.disstid }
            _ui.value = MineUiState(
                profile = profile,
                likedPlaylist = liked,
                favPlaylists = favs,
                // 已登录但资料与歌单全空 → 大概率 key 过期（也可能是网络异常）
                suspectSession = profile == null && all.isEmpty(),
            )
            // 同步红心状态
            ServiceLocator.repository.refreshLikedMids()
        }
    }

    /** 搜索框输入（350ms 防抖） */
    fun onQueryChange(query: String) {
        _search.value = _search.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _search.value = SearchUiState(query = query)
            return
        }
        searchJob = viewModelScope.launch {
            delay(350)
            if (_search.value.query != query) return@launch
            // 记录实际执行的搜索（历史去重、最新在前）
            ServiceLocator.searchHistory.add(query)
            _search.value = _search.value.copy(searching = true, result = null)
            val result = runCatching {
                ServiceLocator.repository.searchAll(query)
            }.getOrDefault(SearchResult())
            if (_search.value.query == query) {
                _search.value = _search.value.copy(searching = false, result = result)
            }
        }
    }

    /** 整列表进队列，从 mid 对应那首开始播（搜索结果点播用） */
    fun playFrom(songs: List<Song>, mid: String) {
        ServiceLocator.player.playFromList(songs, mid)
    }
}
