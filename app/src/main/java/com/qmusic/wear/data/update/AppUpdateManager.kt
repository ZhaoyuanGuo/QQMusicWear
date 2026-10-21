package com.qmusic.wear.data.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.qmusic.wear.CrashLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 应用更新弹窗状态机（Idle/检查失败不打扰 UI） */
sealed class AppUpdateState {
    data object Idle : AppUpdateState()

    /** 有新版本可下载（forced = 低于 minVersionCode，不可跳过） */
    data class Available(val info: UpdateInfo, val forced: Boolean) : AppUpdateState()

    /** APK 下载中（progress 0..1；体积未知时为 -1，UI 显示「下载中…」） */
    data class Downloading(val info: UpdateInfo, val progress: Float) : AppUpdateState()

    /** 下载完成且校验通过，可调起系统安装器 */
    data class ReadyToInstall(val info: UpdateInfo) : AppUpdateState()

    /**
     * 失败。info = null 表示检查阶段失败（仅设置页 Toast 消费，不弹窗）；
     * info 非空表示下载/安装阶段失败（弹窗内展示，可重试/复制链接）。
     */
    data class Failed(val info: UpdateInfo?, val message: String) : AppUpdateState()
}

/**
 * 应用内检查更新（APK 自更新）：
 * - version.json 依次从「轻量应用服务器 → CloudBase → GitHub 代理/直连」拉取，国内直连优先
 * - 启动后台节流检查（24h 一次），新版本弹窗可按版本「以后再说」；forced 不可跳过
 * - 下载落盘到应用私有目录（外部 filesDir/updates），MD5 校验后经 FileProvider 调起安装
 * - 无法调起安装器（ROM 限制）时兜底复制下载链接
 * 服务端发布流程见 website/update/version.json 与 tools/publish-update.js。
 */
object AppUpdateManager {

    /** 更新信息入口（依次尝试；轻量应用服务器国内直连置顶） */
    private val UPDATE_URLS = listOf(
        "http://111.229.33.249/qqmusicwear/update/version.json",
        "https://suspect-d1g6etar94f6301af-1461800616.tcloudbaseapp.com/update-version.json",
        "https://ghproxy.net/https://raw.githubusercontent.com/ZhaoyuanGuo/QQMusicWear/main/website/update/version.json",
        "https://raw.githubusercontent.com/ZhaoyuanGuo/QQMusicWear/main/website/update/version.json",
    )

    private const val TAG = "AppUpdateManager"
    private const val PREFS = "qmusic_update"
    private const val KEY_LAST_AUTO = "last_auto_check_at"
    private const val KEY_DISMISSED = "dismissed_version_code"
    private const val AUTO_INTERVAL_MS = 24 * 60 * 60 * 1000L

    private lateinit var appContext: Context
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var apkDir: File

    /** 本应用 versionCode / versionName（读-改-写在 synchronized 内，@Volatile 保证发布） */
    @Volatile
    private var localVersionCode: Int = 0

    @Volatile
    var localVersionName: String = ""
        private set

    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var workJob: Job? = null
    private val checkLock = Any()

    /** 最近一次检查到的远端信息与各 URL（复制链接兜底用，失败也要能给链接） */
    private var lastInfo: UpdateInfo? = null
    private var lastManifestUrl: String = ""

    fun init(context: Context) {
        this.appContext = context.applicationContext
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        this.apkDir = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "updates",
        ).apply { mkdirs() }
        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            // longVersionCode 为 API 28+ 字段，API 27（如 OPPO Watch）上访问会抛 NoSuchFieldError
            synchronized(checkLock) {
                localVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    pi.longVersionCode.toInt()
                } else {
                    pi.versionCode
                }
                localVersionName = pi.versionName.orEmpty()
            }
        }
    }

    // -----------------------------------------------------------------
    // 检查

    /**
     * 启动后台检查：24h 节流 + 按版本记忆「以后再说」。
     * 任何失败静默（不打扰启动），下次启动/手动检查再试。
     */
    suspend fun checkOnLaunch() {
        if (_state.value !is AppUpdateState.Idle) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_AUTO, 0L) < AUTO_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_AUTO, now).apply()
        val fetched = withContext(Dispatchers.IO) { fetchRemote() } ?: return
        publishIfNewer(fetched, respectDismissed = true)
    }

    /**
     * 设置页手动检查：无视节流与「以后再说」。
     * 返回结果提示文案；有新版本时返回 null（改为弹窗展示，避免 Toast+弹窗双提示）。
     */
    suspend fun checkManually(): String? {
        val fetched = withContext(Dispatchers.IO) { fetchRemote() }
            ?: return "检查失败：更新服务器均不可达"
        if (!fetched.info.isNewerThan(localVersionCode)) {
            return "已是最新版本 v${fetched.info.versionName.ifEmpty { localVersionName }}"
        }
        publishIfNewer(fetched, respectDismissed = false)
        return null
    }

    private fun publishIfNewer(fetched: Fetched, respectDismissed: Boolean) {
        if (!fetched.info.isNewerThan(localVersionCode)) return
        val forced = fetched.info.isForced(localVersionCode)
        if (respectDismissed && !forced &&
            prefs.getInt(KEY_DISMISSED, 0) == fetched.info.versionCode
        ) {
            return
        }
        synchronized(checkLock) {
            lastInfo = fetched.info
            lastManifestUrl = fetched.url
        }
        _state.value = AppUpdateState.Available(fetched.info, forced)
    }

    private data class Fetched(val info: UpdateInfo, val url: String)

    private fun fetchRemote(): Fetched? {
        for ((idx, url) in UPDATE_URLS.withIndex()) {
            try {
                Log.d(TAG, "尝试更新源 $idx: $url")
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val info = resp.body?.string()?.let { parseUpdateInfo(it) } ?: return@use
                    return Fetched(info, url)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "更新源失败 $url: ${t.message}")
            }
        }
        return null
    }

    // -----------------------------------------------------------------
    // 下载与安装

    /** 下载当前提示的新版本（重复调用被忽略），进度/结果经 [state] 流转 */
    fun startDownload() {
        val info = (_state.value as? AppUpdateState.Available)?.info
            ?: (_state.value as? AppUpdateState.Failed)?.info
            ?: lastInfo ?: return
        if (workJob?.isActive == true) return
        val manifestUrl = synchronized(checkLock) { lastManifestUrl.ifEmpty { UPDATE_URLS.first() } }
        workJob = scope.launch {
            runCatching { downloadApk(info, resolveApkUrl(manifestUrl, info.apkUrl)) }
                .onSuccess { _state.value = AppUpdateState.ReadyToInstall(info) }
                .onFailure { t ->
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    CrashLog.log(t)
                    _state.value = AppUpdateState.Failed(info, "下载失败：${t.message ?: "网络异常"}")
                }
        }
    }

    private suspend fun downloadApk(info: UpdateInfo, url: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("空响应")
            val total = body.contentLength().takeIf { it > 0 } ?: info.sizeBytes.takeIf { it > 0 } ?: -1L
            // 文件名过滤路径非法字符（versionName 来自服务器，不可信）
            val safeName = info.versionName.map { if (it.isLetterOrDigit()) it else '.' }
                .joinToString("").trim('.').ifEmpty { "v${info.versionCode}" }
            val tmp = File(apkDir, "download.tmp")
            val final = File(apkDir, "QQMusicWear-$safeName.apk")
            try {
                var read = 0L
                // 进度按 1% 步进上报，避免每个数据块都触发重组刷 UI
                var lastPercent = -1
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            // 用户关闭弹窗（dismiss → Idle）视为取消：阻塞 IO 无 suspension 点，主动退出
                            if (_state.value is AppUpdateState.Idle) {
                                throw kotlinx.coroutines.CancellationException("用户取消下载")
                            }
                            val progress = if (total > 0) {
                                (read.toFloat() / total).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }
                            val pct = if (progress >= 0f) (progress * 100).toInt() else -1
                            if (pct != lastPercent) {
                                lastPercent = pct
                                _state.value = AppUpdateState.Downloading(info, progress)
                            }
                        }
                    }
                }
                if (read <= 0) error("内容为空")
                if (total > 0 && read < total) error("下载不完整 ($read/$total)")
                if (info.md5.isNotEmpty()) {
                    val actual = md5Of(tmp)
                    if (actual != info.md5) error("安装包校验失败（MD5 不匹配）")
                }
                if (final.exists()) final.delete()
                if (!tmp.renameTo(final)) error("保存文件失败")
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }
    }

    private fun md5Of(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 已下载完成时调起系统安装器；返回 null=已拉起，非 null=失败原因（UI 提示复制链接兜底）。
     * 安装确认后本进程可能被系统杀掉，无需收尾。
     */
    fun install(): String? {
        val st = _state.value as? AppUpdateState.ReadyToInstall ?: return "暂无已下载的更新包"
        return runCatching {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                File(apkDir, apkFileNameOf(st.info)),
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                )
            }
            appContext.startActivity(intent)
        }.onFailure { t ->
            CrashLog.log(t)
            Log.w(TAG, "调起安装失败", t)
        }.fold(
            onSuccess = { null },
            onFailure = {
                "系统未授权或无安装器，请到「设置-安全」允许安装应用，或复制下载链接在手机上安装"
            },
        )
    }

    /** 与下载落盘同规则的文件名（versionName 清洗后拼 .apk） */
    private fun apkFileNameOf(info: UpdateInfo): String {
        val safeName = info.versionName.map { if (it.isLetterOrDigit()) it else '.' }
            .joinToString("").trim('.').ifEmpty { "v${info.versionCode}" }
        return "QQMusicWear-$safeName.apk"
    }

    /** 该版本对当前安装包是否构成强制更新 */
    fun isForced(info: UpdateInfo): Boolean = info.isForced(localVersionCode)

    /** 复制当前版本下载链接（GitHub/服务器均不可达时的手动兜底）；无已知版本时返回空串 */
    fun copyDownloadUrl(): String {
        val info = lastInfo ?: return ""
        val manifestUrl = synchronized(checkLock) { lastManifestUrl.ifEmpty { UPDATE_URLS.first() } }
        return resolveApkUrl(manifestUrl, info.apkUrl)
    }

    /** 关闭弹窗：记录「以后再说」；下载中被取消则撤销任务并回到 Idle */
    fun dismiss() {
        val cur = _state.value
        if (cur is AppUpdateState.Available && !cur.forced) {
            prefs.edit().putInt(KEY_DISMISSED, cur.info.versionCode).apply()
        }
        if (cur is AppUpdateState.Downloading) {
            workJob?.cancel()
            runCatching { File(apkDir, "download.tmp").delete() }
        }
        _state.value = AppUpdateState.Idle
    }
}
