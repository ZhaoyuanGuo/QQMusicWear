package com.qmusic.wear

import android.app.Application
import android.content.Context
import com.qmusic.wear.data.api.Credential
import com.qmusic.wear.data.api.QMusicApi
import com.qmusic.wear.data.auth.QrLoginManager
import com.qmusic.wear.data.player.PlayerConnection
import com.qmusic.wear.data.repo.MusicRepository
import com.qmusic.wear.data.store.CredentialStore
import com.qmusic.wear.data.store.HistoryStore
import com.qmusic.wear.data.store.SettingsStore
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 轻量 ServiceLocator */
object ServiceLocator {
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            CrashLog.log(e)
        },
    )

    private lateinit var appContext: Context

    lateinit var credentialStore: CredentialStore
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var historyStore: HistoryStore
        private set
    lateinit var api: QMusicApi
        private set
    lateinit var repository: MusicRepository
        private set
    lateinit var qrLogin: QrLoginManager
        private set
    lateinit var player: PlayerConnection
        private set
    lateinit var downloads: com.qmusic.wear.data.download.DownloadManager
        private set
    lateinit var searchHistory: com.qmusic.wear.data.store.SearchHistoryStore
        private set
    lateinit var agreementStore: com.qmusic.wear.data.store.AgreementStore
        private set

    private val _credential = MutableStateFlow(Credential.EMPTY)
    val credential: StateFlow<Credential> = _credential.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        CrashLog.init(context)
        credentialStore = CredentialStore(appContext)
        settingsStore = SettingsStore(appContext)
        historyStore = HistoryStore(appContext)
        com.qmusic.wear.data.source.SourceManager.init(appContext, { _credential.value }) { raw ->
            // 源插件全局事件：凭据过期 → 清除本地凭据并提示重登（仅登录态触发一次）
            if (raw.contains("CredentialExpired") && _credential.value.isLogged) {
                onLogout()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        appContext,
                        "登录已过期，请重新登录",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        api = QMusicApi { _credential.value }
        repository = MusicRepository(api)
        qrLogin = QrLoginManager()
        player = PlayerConnection(appContext, historyStore)
        downloads = com.qmusic.wear.data.download.DownloadManager(appContext, api.http)
        searchHistory = com.qmusic.wear.data.store.SearchHistoryStore(appContext)
        agreementStore = com.qmusic.wear.data.store.AgreementStore(appContext)

        appScope.launch {
            credentialStore.credentialFlow.collect { cred ->
                val prev = _credential.value
                // 保留已知昵称/头像，避免每次冷启动重复拉取
                _credential.value = if (cred.isLogged && cred.nick.isEmpty() && prev.isLogged && prev.musicid == cred.musicid) {
                    cred.copy(nick = prev.nick, avatarUrl = prev.avatarUrl)
                } else cred
            }
        }
    }

    fun onLoginSuccess(cred: Credential) {
        appScope.launch {
            runCatching { credentialStore.save(cred) }.onFailure { CrashLog.log(it) }
        }
        _credential.value = cred
    }

    fun onLogout() {
        appScope.launch { credentialStore.clear() }
        _credential.value = Credential.EMPTY
    }

    /**
     * 登录后从资料接口补全 encryptUin（收藏歌单接口必需）。
     * 旧版登录凭据缺该字段时无需重新扫码。
     */
    fun onEncryptUinObtained(euin: String) {
        if (euin.isEmpty()) return
        val cur = _credential.value
        if (!cur.isLogged || cur.encryptUin.isNotEmpty()) return
        val merged = cur.copy(encryptUin = euin)
        _credential.value = merged
        appScope.launch {
            runCatching { credentialStore.save(merged) }.onFailure { CrashLog.log(it) }
        }
    }

    fun appContextOrNull(): Context? = if (ServiceLocator::appContext.isInitialized) appContext else null
}

/** 崩溃/未捕获异常落盘，主页展示摘要便于定位 */
object CrashLog {
    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun log(e: Throwable) {
        runCatching {
            if (CrashLog::context.isInitialized) {
                java.io.File(context.filesDir, "crash.log")
                    .appendText("\n----\n${System.currentTimeMillis()} ${e.stackTraceToString().take(1500)}")
            }
        }
    }
}

class QMusicApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        // 未捕获异常落盘后交回系统，避免闪退无迹可循
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            CrashLog.log(e)
            previous?.uncaughtException(t, e)
        }
        ServiceLocator.init(this)
    }

    /**
     * 全局图片加载器：走 OkHttp 网络引擎（Coil3 默认不含网络支持，必须显式配置），
     * 防盗链请求头由音乐源插件 manifest 提供（imageHostSuffix + imageHeaders），
     * 并开启淡入过渡。
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(20, TimeUnit.SECONDS)
                                .addInterceptor { chain ->
                                    val rules = com.qmusic.wear.data.source.SourceManager.imageRules
                                    val b = chain.request().newBuilder()
                                    if (rules != null &&
                                        chain.request().url.host.endsWith(rules.first)
                                    ) {
                                        rules.second.forEach { (k, v) -> b.header(k, v) }
                                    }
                                    chain.proceed(b.build())
                                }
                                .build()
                        },
                    ),
                )
            }
            .crossfade(true)
            .build()
}
