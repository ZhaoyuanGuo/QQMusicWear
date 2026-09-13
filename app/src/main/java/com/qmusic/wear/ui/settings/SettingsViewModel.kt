package com.qmusic.wear.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.model.UserProfile
import com.qmusic.wear.data.player.SleepTimer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 设置页 UI 状态 */
data class SettingsUiState(
    val profile: UserProfile? = null,
    /** 播放音质 */
    val quality: Quality = Quality.STANDARD,
    /** 下载音质（独立于播放音质） */
    val downloadQuality: Quality = Quality.HIGH,
    /** 睡眠定时（分钟，0=关闭） */
    val sleepMinutes: Int = 0,
    val showLogoutConfirm: Boolean = false,
    /** 播放链路自检：null=未跑，""=跑动中，非空=结果 */
    val selfTestResult: String? = null,
)

class SettingsViewModel : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        // 登录/退出后本页即时刷新
        viewModelScope.launch {
            ServiceLocator.credential.collect { load() }
        }
    }

    fun load() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                quality = ServiceLocator.settingsStore.quality(),
                downloadQuality = ServiceLocator.settingsStore.downloadQuality(),
            )
            val cred = ServiceLocator.credential.value
            if (cred.isLogged) {
                val profile = runCatching {
                    ServiceLocator.repository.profile(cred)
                }.getOrNull() ?: UserProfile(cred.musicid, cred.nick, cred.avatarUrl)
                _ui.value = _ui.value.copy(profile = profile)
            } else {
                _ui.value = _ui.value.copy(profile = null)
            }
        }
    }

    fun selectQuality(quality: Quality) {
        _ui.value = _ui.value.copy(quality = quality)
        viewModelScope.launch {
            ServiceLocator.settingsStore.setQuality(quality)
        }
    }

    fun selectDownloadQuality(quality: Quality) {
        _ui.value = _ui.value.copy(downloadQuality = quality)
        viewModelScope.launch {
            ServiceLocator.settingsStore.setDownloadQuality(quality)
        }
    }

    /** 睡眠定时：到时自动暂停（0=取消） */
    fun selectSleepTimer(minutes: Int) {
        _ui.value = _ui.value.copy(sleepMinutes = minutes)
        SleepTimer.start(minutes) { ServiceLocator.player.pause() }
    }

    fun requestLogout() {
        _ui.value = _ui.value.copy(showLogoutConfirm = true)
    }

    /** 播放链路自检：vkey 三组探针（免费/双mid/付费歌），结果弹窗展示 */
    fun runSelfTest() {
        _ui.value = _ui.value.copy(selfTestResult = "")
        viewModelScope.launch {
            val result = runCatching { ServiceLocator.repository.selfTest() }
                .getOrElse { "自检异常: ${it.message?.take(60)}" }
            _ui.value = _ui.value.copy(selfTestResult = result)
        }
    }

    fun dismissSelfTest() {
        _ui.value = _ui.value.copy(selfTestResult = null)
    }

    fun cancelLogout() {
        _ui.value = _ui.value.copy(showLogoutConfirm = false)
    }

    fun confirmLogout() {
        ServiceLocator.onLogout()
        _ui.value = _ui.value.copy(
            profile = null,
            showLogoutConfirm = false,
        )
    }
}
