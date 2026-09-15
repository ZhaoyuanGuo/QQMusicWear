package com.qmusic.wear.data.source

import android.content.Context
import android.util.Log
import com.qmusic.wear.CrashLog
import com.qmusic.wear.data.model.Quality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** 音乐源状态 */
sealed class SourceState {
    /** 尚未下载（首启或缓存被清） */
    data object Missing : SourceState()

    /** 下载中（progress 0..1） */
    data class Downloading(val progress: Float) : SourceState()

    /** 就绪（含源版本号与来源 URL） */
    data class Ready(val version: Int, val fromUrl: String) : SourceState()

    /** 下载/加载失败 */
    data class Failed(val message: String) : SourceState()
}

/**
 * 音乐源管理器：
 * - 首启（同意协议后）从镜像列表自动下载源脚本，缓存到私有目录
 * - APK 内不含任何协议明文；这里只保留下载入口（开发者自己的仓库/CDN 镜像）
 * - Ready 后惰性创建 [SourceEngine]；全部业务调用经 [call] 转发
 */
object SourceManager {

    /** 下载入口镜像（指向开源仓库的源脚本，依次尝试） */
    private val MIRRORS = listOf(
        "https://cdn.jsdelivr.net/gh/ZhaoyuanGuo/QQMusicWear@main/source/qmusic_source.js",
        "https://fastly.jsdelivr.net/gh/ZhaoyuanGuo/QQMusicWear@main/source/qmusic_source.js",
        "https://ghproxy.net/https://raw.githubusercontent.com/ZhaoyuanGuo/QQMusicWear/main/source/qmusic_source.js",
        "https://raw.githubusercontent.com/ZhaoyuanGuo/QQMusicWear/main/source/qmusic_source.js",
    )

    private const val FILE_NAME = "qmusic_source.js"
    private const val TAG = "SourceManager"

    private lateinit var filesDir: java.io.File
    private lateinit var credentialProvider: () -> com.qmusic.wear.data.api.Credential

    /** 全局源事件（凭据过期等），由宿主在 init 时挂载 */
    @Volatile
    private var onSourceEvent: (String) -> Unit = {}

    /** 本应用 versionCode，用于 manifest.minAppVersion 兼容性校验 */
    @Volatile
    private var appVersionCode: Int = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<SourceState>(SourceState.Missing)
    val state: StateFlow<SourceState> = _state.asStateFlow()

    private val mutex = Mutex()

    @Volatile
    private var engine: SourceEngine? = null

    /** 图片加载规则（manifest.imageRules），Ready 后生效 */
    @Volatile
    var imageRules: Pair<String, Map<String, String>>? = null // hostSuffix to headers
        private set

    /** 播放/下载请求头（manifest.playbackHeaders），Ready 后生效 */
    @Volatile
    var playbackHeaders: Map<String, String> = emptyMap()
        private set

    /** 文件名前缀 -> 音质（manifest.qualityPrefixes），供播放页展示 */
    @Volatile
    var prefixToQuality: Map<String, Quality> = emptyMap()
        private set

    fun init(
        context: Context,
        credentialProvider: () -> com.qmusic.wear.data.api.Credential,
        onSourceEvent: (String) -> Unit = {},
    ) {
        this.filesDir = java.io.File(context.filesDir, "sources").apply { mkdirs() }
        this.credentialProvider = credentialProvider
        this.onSourceEvent = onSourceEvent
        this.appVersionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(0)
        scope.launch { loadCached() }
    }

    /** 当前源版本（未就绪返回 0） */
    fun currentVersion(): Int = (_state.value as? SourceState.Ready)?.version ?: 0

    /**
     * 确保源就绪：有缓存直接加载；无缓存自动下载。
     * 返回 true 表示就绪。
     */
    suspend fun ensureReady(): Boolean {
        if (_state.value is SourceState.Ready) return true
        return if (hasCache()) loadCached() else downloadNow()
    }

    /** 从镜像下载源脚本（设置页「更新音乐源」也走这里）；已就绪时失败不改变状态 */
    suspend fun downloadNow(): Boolean = mutex.withLock {
        val wasReady = engine != null
        if (!wasReady) _state.value = SourceState.Downloading(0f)
        val script = fetchFromMirrors(reportProgress = !wasReady)
        if (script == null) {
            if (!wasReady) _state.value = SourceState.Failed("下载失败：所有镜像均不可达")
            return false
        }
        if (!script.contains("qmu.register")) {
            if (!wasReady) _state.value = SourceState.Failed("下载失败：源文件内容无效")
            return false
        }
        val sigError = SourceVerifier.verify(script)
        if (sigError != null) {
            Log.w(TAG, "源签名校验失败: $sigError")
            if (!wasReady) _state.value = SourceState.Failed("安全校验未通过（$sigError），已拒绝加载")
            return false
        }
        val ok = installScript(script, "网络下载")
        if (ok) {
            persistCache(script)
        } else if (!wasReady) {
            _state.value = SourceState.Failed("源脚本加载失败")
        }
        return ok
    }

    /** 业务调用入口：转发到引擎处理器，异常统一抛出；期间挂载全局事件回调 */
    suspend fun call(name: String, argsJson: String): String {
        val e = engine ?: error("音乐源未就绪")
        e.eventSink = { raw -> runCatching { onSourceEvent(raw) } }
        try {
            return e.call(name, argsJson)
        } finally {
            e.eventSink = null
        }
    }

    /** 带事件回调的调用（扫码登录）：期间挂载 sink，结束后清除 */
    suspend fun callWithEvents(name: String, argsJson: String, sink: (String) -> Unit): String {
        val e = engine ?: error("音乐源未就绪")
        e.eventSink = sink
        try {
            return e.call(name, argsJson)
        } finally {
            e.eventSink = null
        }
    }

    /** 引擎是否就绪 */
    fun isReady(): Boolean = engine != null

    /**
     * 从存储导入源脚本（镜像全部失效时的兜底）。
     * 返回 null = 成功，非 null = 失败原因；同样强制签名校验。
     */
    suspend fun importScript(script: String): String? = mutex.withLock {
        val wasReady = engine != null
        val sigError = SourceVerifier.verify(script)
            ?: if (script.contains("qmu.register")) null else "源文件内容无效"
        if (sigError != null) {
            if (!wasReady) _state.value = SourceState.Failed("导入失败：$sigError")
            return sigError
        }
        val ok = installScript(script, "本地导入")
        if (ok) {
            persistCache(script)
            null
        } else {
            if (!wasReady) _state.value = SourceState.Failed("导入失败：源脚本加载失败")
            "源脚本加载失败"
        }
    }

    // -----------------------------------------------------------------

    private fun hasCache(): Boolean = java.io.File(filesDir, FILE_NAME).exists()

    private suspend fun loadCached(): Boolean = mutex.withLock {
        val f = java.io.File(filesDir, FILE_NAME)
        if (!f.exists()) {
            _state.value = SourceState.Missing
            return false
        }
        val script = f.readText()
        if (SourceVerifier.verify(script) != null) {
            // 缓存被篡改/损坏：删除并回到 Missing，等待重新下载
            f.delete()
            _state.value = SourceState.Missing
            return false
        }
        val ok = installScript(script, "本地缓存")
        if (!ok) {
            // 缓存损坏：删除并回到 Missing，等待重新下载
            f.delete()
            _state.value = SourceState.Missing
        }
        return ok
    }

    private fun fetchFromMirrors(reportProgress: Boolean = true): String? {
        for ((idx, url) in MIRRORS.withIndex()) {
            try {
                if (reportProgress) {
                    _state.value = SourceState.Downloading(idx.toFloat() / MIRRORS.size)
                }
                Log.d(TAG, "尝试镜像 $idx: $url")
                downloadClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string().orEmpty()
                    if (body.length > 200) return body
                }
            } catch (t: Throwable) {
                Log.w(TAG, "镜像失败 $url: ${t.message}")
            }
        }
        return null
    }

    /** 编译并注册源脚本；成功后进入 Ready 并预读 manifest 派生信息 */
    private suspend fun installScript(script: String, from: String): Boolean {
        return try {
            val eng = SourceEngine(
                credentialProvider = {
                    val c = credentialProvider()
                    CredentialSnapshot(
                        musicid = c.musicid,
                        musickey = c.musickey,
                        strMusicid = c.strMusicid,
                        encryptUin = c.encryptUin,
                        nick = c.nick,
                        avatarUrl = c.avatarUrl,
                    )
                },
            )
            val manifest = eng.evaluate(script)
            val manifestObj = runCatching {
                Json.parseToJsonElement(manifest).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))

            val version = manifestObj["version"].toString().filter { it.isDigit() }.toIntOrNull() ?: 1

            // 兼容性闸门：新源可能依赖新桥接 API，旧 APK 一律拒绝加载
            val minApp = manifestObj["minAppVersion"].toString().filter { it.isDigit() }.toIntOrNull() ?: 0
            if (minApp > appVersionCode) {
                eng.shutdown()
                error("源要求 APK ≥ v$minApp，请先更新应用")
            }

            // 派生：图片规则 / 播放头 / 音质前缀映射
            (manifestObj["imageHostSuffix"] as? kotlinx.serialization.json.JsonPrimitive)?.let {
                val headers = manifestObj.jsonHeaders("imageHeaders")
                if (it.content.isNotEmpty()) imageRules = it.content to headers
            }
            playbackHeaders = manifestObj.jsonHeaders("playbackHeaders")
            prefixToQuality = parseQualityPrefixes(manifestObj)

            engine?.shutdown()
            engine = eng
            _state.value = SourceState.Ready(version, from)
            Log.d(TAG, "音乐源就绪 v$version（$from）")
            true
        } catch (t: Throwable) {
            CrashLog.log(t)
            Log.e(TAG, "源加载失败", t)
            false
        }
    }

    private fun persistCache(script: String) {
        runCatching {
            java.io.File(filesDir, FILE_NAME).writeText(script)
        }.onFailure { CrashLog.log(it) }
    }

    private fun JsonObject.jsonHeaders(key: String): Map<String, String> {
        val obj = this[key] as? JsonObject ?: return emptyMap()
        return obj.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "" }
            .filterValues { it.isNotEmpty() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseQualityPrefixes(manifest: JsonObject): Map<String, Quality> {
        val obj = manifest["qualityPrefixes"] as? JsonObject ?: return emptyMap()
        val out = mutableMapOf<String, Quality>()
        obj.forEach { (qualityName, prefixesEl) ->
            val q = Quality.entries.firstOrNull { it.name == qualityName } ?: return@forEach
            (prefixesEl as? kotlinx.serialization.json.JsonArray)?.forEach { p ->
                (p as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { out[it] = q }
            }
        }
        return out
    }
}
