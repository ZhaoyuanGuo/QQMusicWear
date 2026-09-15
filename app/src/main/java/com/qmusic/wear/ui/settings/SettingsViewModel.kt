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
    /** 开屏提示（启动 Toast）开关 */
    val launchToastEnabled: Boolean = true,
    /** 日志提取状态：null=空闲，""=提取中，非空=结果提示 */
    val logExportMessage: String? = null,
    /** 音乐源版本 */
    val sourceVersion: Int = 0,
    /** 源更新状态：null=空闲，""=更新中，非空=结果提示 */
    val sourceUpdateMessage: String? = null,
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
                launchToastEnabled = ServiceLocator.settingsStore.launchToastFlow.value,
                sourceVersion = com.qmusic.wear.data.source.SourceManager.currentVersion(),
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

    /** 开屏提示开关：控制启动时是否弹「仅供学习交流使用」Toast */
    fun setLaunchToast(enabled: Boolean) {
        _ui.value = _ui.value.copy(launchToastEnabled = enabled)
        ServiceLocator.settingsStore.setLaunchToast(enabled)
    }

    /** 提取日志：崩溃落盘 + 本进程 logcat 尾部，写出为文本文件并返回展示提示 */
    fun extractLog() {
        if (_ui.value.logExportMessage == "") return
        _ui.value = _ui.value.copy(logExportMessage = "")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ctx = ServiceLocator.appContextOrNull() ?: run {
                _ui.value = _ui.value.copy(logExportMessage = "提取失败：应用未初始化")
                return@launch
            }
            val sb = StringBuilder()
            runCatching {
                val f = java.io.File(ctx.filesDir, "crash.log")
                if (f.exists()) sb.append("== crash.log ==\n").append(f.readText().takeLast(8000))
            }
            runCatching {
                val p = android.os.Process.myPid()
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$p", "-t", "800"))
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                sb.append("\n\n== logcat(pid=").append(p).append(") ==\n").append(out.takeLast(12000))
            }
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            val out = java.io.File(dir, "qmusic_log_${System.currentTimeMillis()}.txt")
            runCatching { out.writeText(sb.toString()) }
            val msg = if (sb.isEmpty()) "暂无日志" else "已导出 ${out.absolutePath}"
            _ui.value = _ui.value.copy(logExportMessage = msg)
        }
    }

    fun dismissLogExport() {
        _ui.value = _ui.value.copy(logExportMessage = null)
    }

    /** 更新音乐源：从镜像重新下载并重载引擎，结果经 Toast 提示 */
    fun updateSource() {
        if (_ui.value.sourceUpdateMessage == "") return
        _ui.value = _ui.value.copy(sourceUpdateMessage = "")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val ok = runCatching { com.qmusic.wear.data.source.SourceManager.downloadNow() }
                .getOrDefault(false)
            _ui.value = _ui.value.copy(
                sourceUpdateMessage = if (ok) {
                    "音乐源已更新到 v${com.qmusic.wear.data.source.SourceManager.currentVersion()}"
                } else {
                    "音乐源更新失败：所有镜像均不可达"
                },
                sourceVersion = com.qmusic.wear.data.source.SourceManager.currentVersion(),
            )
        }
    }

    /** 从存储导入源文件（镜像全部失效时的兜底），结果经 Toast 提示 */
    fun importSource(bytes: ByteArray) {
        if (_ui.value.sourceUpdateMessage == "") return
        _ui.value = _ui.value.copy(sourceUpdateMessage = "")
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val err = runCatching {
                com.qmusic.wear.data.source.SourceManager.importScript(
                    bytes.toString(Charsets.UTF_8),
                )
            }.getOrDefault("读取文件失败")
            _ui.value = _ui.value.copy(
                sourceUpdateMessage = err ?: "音乐源导入成功（v${com.qmusic.wear.data.source.SourceManager.currentVersion()}）",
                sourceVersion = com.qmusic.wear.data.source.SourceManager.currentVersion(),
            )
        }
    }

    fun dismissSourceUpdate() {
        _ui.value = _ui.value.copy(sourceUpdateMessage = null)
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
