package com.qmusic.wear.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.HomeCard
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 主页 UI 状态 */
data class HomeUiState(
    val loading: Boolean = true,
    /** 首页大卡（随音乐源变化；可变数量） */
    val cards: List<HomeCard> = emptyList(),
    /** 扁平推荐列表（每日推荐二级页用） */
    val songs: List<Song> = emptyList(),
)

class HomeViewModel : ViewModel() {

    private val _ui = MutableStateFlow(HomeUiState())
    val ui: StateFlow<HomeUiState> = _ui.asStateFlow()

    /** 上次加载所用的源 id，用于判断切源后是否需要重载 */
    private var loadedSourceId: String = ""

    init {
        // 登录态变化时自动重载（登录/退出后推荐内容跟随切换）
        viewModelScope.launch {
            ServiceLocator.credential.collect { load() }
        }
        // 切换音乐源时自动重载（首页推送随源变化）
        viewModelScope.launch {
            SourceManager.activeSourceFlow.collect {
                if (loadedSourceId.isNotEmpty() && loadedSourceId != it.id) load()
                loadedSourceId = it.id
            }
        }
    }

    /** 加载首页推送（各源 homeFeed；源未实现时回退到旧推荐逻辑） */
    fun load() {
        loadedSourceId = SourceManager.activeSource.id
        viewModelScope.launch {
            _ui.value = HomeUiState(loading = true)
            // 切源后引擎可能尚未就绪：先等 Ready 再取数据，否则会拿到空列表退回兜底卡
            runCatching {
                SourceManager.state.first { it is com.qmusic.wear.data.source.SourceState.Ready }
            }
            val cred = ServiceLocator.credential.value
            val cards = runCatching { ServiceLocator.repository.homeFeed() }.getOrDefault(emptyList())
            // 每日推荐二级页的扁平列表：优先取源给的每日卡，其次旧推荐接口
            val dailySongs = cards.firstOrNull { it.action == "daily" || it.action == "songs" }
                ?.songs.orEmpty()
            val songs = dailySongs.ifEmpty {
                runCatching { ServiceLocator.repository.recommendSongs(cred) }.getOrDefault(emptyList())
            }
            _ui.value = HomeUiState(
                loading = false,
                cards = cards.ifEmpty { legacyCards(songs) },
                songs = songs,
            )
            android.util.Log.d(
                "HomeVM",
                "source=${SourceManager.activeSource.id} feedCards=${cards.size} " +
                    "dailySongs=${songs.size} err=${ServiceLocator.repository.lastHomeFeedError}",
            )
        }
    }

    /** 源未实现 homeFeed 时的旧版四张大卡兜底 */
    private fun legacyCards(songs: List<Song>): List<HomeCard> {
        val rep = songs.firstOrNull()
        val lucky = songs.randomOrNull()
        return buildList {
            add(
                HomeCard(
                    id = "daily",
                    title = "每日30首",
                    subtitle = if (songs.isNotEmpty()) "为你推荐 · ${songs.size} 首" else "正在加载…",
                    action = "daily",
                    coverUrl = rep?.cover300.orEmpty(),
                    songName = rep?.name.orEmpty(),
                    singers = rep?.singers.orEmpty(),
                    songs = songs,
                ),
            )
            if (lucky != null) {
                add(
                    HomeCard(
                        id = "guess",
                        title = "猜你想听",
                        subtitle = "私人雷达",
                        action = "daily",
                        coverUrl = lucky.cover300,
                        songName = lucky.name,
                        singers = lucky.singers,
                        songs = songs,
                    ),
                )
            }
            add(HomeCard(id = "rank", title = "排行榜", subtitle = "官方榜单", action = "rank"))
            add(HomeCard(id = "square", title = "歌单广场", subtitle = "官方精选歌单", action = "square"))
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