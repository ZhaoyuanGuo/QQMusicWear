package com.qmusic.wear.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.PlayMode
import com.qmusic.wear.data.model.Quality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 下载状态（播放页下载按钮） */
data class DownloadUi(
    val running: Boolean = false,
    val progress: Float = 0f,
    val done: Boolean = false,
    val message: String = "",
)

/** 播放页 UI 状态（音量面板 / 音质面板开关与内容） */
data class PlayerUiState(
    val showVolume: Boolean = false,
    val showQuality: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 0,
    val selectedQuality: Quality = Quality.STANDARD,
    val switching: Boolean = false,
    val download: DownloadUi = DownloadUi(),
)

class PlayerViewModel : ViewModel() {

    private val _ui = MutableStateFlow(PlayerUiState())
    val ui: StateFlow<PlayerUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val quality = ServiceLocator.settingsStore.quality()
            _ui.value = _ui.value.copy(
                selectedQuality = quality,
                volume = ServiceLocator.player.currentVolume,
                maxVolume = ServiceLocator.player.maxVolume,
            )
        }
        // 打开播放页时同步一次红心状态（「我喜欢」歌单）
        viewModelScope.launch {
            ServiceLocator.repository.refreshLikedMids()
        }
    }

    /** 下载当前歌曲到本地（已下载则跳过；使用「下载音质」设置） */
    fun downloadCurrent() {
        val song = ServiceLocator.player.state.value.song ?: return
        if (ServiceLocator.downloads.isDownloaded(song.mid)) {
            _ui.value = _ui.value.copy(
                download = DownloadUi(done = true, message = "已下载"),
            )
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                download = DownloadUi(running = true, progress = 0f),
            )
            try {
                val quality = ServiceLocator.settingsStore.downloadQuality()
                val resolved = ServiceLocator.repository.resolveForDownload(song, quality)
                    ?: error("无法获取下载地址（可能需要登录或 VIP）")
                ServiceLocator.downloads.download(song, resolved) { p ->
                    _ui.value = _ui.value.copy(
                        download = DownloadUi(running = true, progress = p),
                    )
                }
                _ui.value = _ui.value.copy(
                    download = DownloadUi(done = true, message = "下载完成"),
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    download = DownloadUi(message = t.message?.take(40) ?: "下载失败"),
                )
            }
        }
    }

    fun toggleVolume() {
        _ui.value = _ui.value.copy(showVolume = !_ui.value.showVolume, showQuality = false)
    }

    fun toggleQuality() {
        _ui.value = _ui.value.copy(showQuality = !_ui.value.showQuality, showVolume = false)
    }

    fun setVolume(value: Int) {
        ServiceLocator.player.setVolume(value)
        _ui.value = _ui.value.copy(volume = value)
    }

    /** 顺序 -> 单曲循环 -> 随机 -> 顺序 */
    fun cyclePlayMode() {
        val next = when (ServiceLocator.player.playMode.value) {
            PlayMode.SEQUENTIAL -> PlayMode.REPEAT_ONE
            PlayMode.REPEAT_ONE -> PlayMode.RANDOM
            PlayMode.RANDOM -> PlayMode.SEQUENTIAL
        }
        ServiceLocator.player.setPlayMode(next)
    }

    /** 切换音质：按新音质重新解析当前队列并无缝续播 */
    fun selectQuality(quality: Quality) {
        if (quality == _ui.value.selectedQuality) {
            toggleQuality()
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(switching = true)
            ServiceLocator.settingsStore.setQuality(quality)
            val now = ServiceLocator.player.state.value
            val urls = ServiceLocator.repository.resolveUrls(now.queue, quality)
            ServiceLocator.player.switchQuality(urls)
            _ui.value = _ui.value.copy(
                switching = false,
                selectedQuality = quality,
                showQuality = false,
            )
        }
    }

    /** 由当前生效的文件名前缀得到音质名（前缀映射由音乐源插件提供） */
    fun currentQualityLabel(qualityPrefix: String): String {
        if (qualityPrefix.isEmpty()) return "标准"
        return com.qmusic.wear.data.source.SourceManager.prefixToQuality[qualityPrefix]?.label ?: "标准"
    }

    companion object {
        fun allQualities(): List<Quality> = Quality.entries
    }
}
