package com.qmusic.wear

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qmusic.wear.data.source.SourceManager
import com.qmusic.wear.data.source.SourceState
import com.qmusic.wear.ui.agreement.AgreementScreen
import com.qmusic.wear.ui.components.LiveCapsule
import com.qmusic.wear.ui.source.SourceGateScreen
import com.qmusic.wear.ui.theme.LocalIsRoundScreen
import com.qmusic.wear.ui.theme.LocalLowPerf
import kotlinx.coroutines.launch

/**
 * 应用根组合：协议门控 → 音乐源门控 → 页面导航（[AppNavHost]）+ 全局实况胶囊。
 * 页面路由表见 AppRoutes.kt。
 */
@Composable
internal fun AppRoot() {
    // —— 用户协议：首启（或协议更新后）必须同意才能进入应用 ——
    val agreementStore = remember { ServiceLocator.agreementStore }
    var agreed by remember { mutableStateOf(agreementStore.isAgreed) }
    if (!agreed) {
        AgreementScreen(
            onAgree = {
                agreementStore.setAgreed()
                agreed = true
            },
        )
        return
    }

    // —— 音乐源：协议后自动下载/加载（协议实现在外部源插件，APK 无明文） ——
    val sourceState by SourceManager.state.collectAsStateWithLifecycle()
    if (sourceState !is SourceState.Ready) {
        val retryScope = rememberCoroutineScope()
        val gateContext = LocalContext.current
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                retryScope.launch {
                    val result = runCatching {
                        gateContext.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        }
                    }.getOrNull()
                    val err = when {
                        result == null -> "读取文件失败"
                        else -> SourceManager.importScript(result)
                    }
                    android.widget.Toast.makeText(
                        gateContext,
                        err ?: "音乐源导入成功",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
        // 自动触发一次（key=Unit）：有缓存走 loadCached（不发网络请求），无缓存才下载。
        // 之前直接 downloadNow 会在「缓存加载完成 → 门控页退场」时被组合作用域取消，
        // 产生无意义的"源加载失败"+ crash.log 记录。
        // 仅 Missing 态触发；Failed 态等待用户手动「重试」，避免失败重试循环。
        LaunchedEffect(Unit) {
            if (SourceManager.state.value is SourceState.Missing) {
                SourceManager.ensureReady()
            }
        }
        val activeSource by SourceManager.activeSourceFlow.collectAsStateWithLifecycle()
        SourceGateScreen(
            state = sourceState,
            activeId = activeSource.id,
            onSelect = { id -> retryScope.launch { SourceManager.selectSource(id) } },
            onRetry = { retryScope.launch { SourceManager.downloadNow() } },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
        )
        return
    }

    val nav = rememberSaveable(saver = ScreenNav.Saver) { ScreenNav() }
    val navScope = rememberCoroutineScope()

    // 开屏提示（可在设置页开关）
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (ServiceLocator.settingsStore.launchToastFlow.value) {
            android.widget.Toast.makeText(ctx, "仅供学习交流使用", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // 显示模式：开启屏幕常亮后，播放/歌词页保持屏幕点亮，离开页面自动清除
    val keepScreenOn by ServiceLocator.settingsStore.keepScreenOnFlow.collectAsStateWithLifecycle()
    DisposableEffect(nav.screen, keepScreenOn) {
        val win = (ctx as? android.app.Activity)?.window
        if (keepScreenOn && (nav.screen == Screen.Player || nav.screen == Screen.Lyrics)) {
            win?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Android 13+ 通知权限（媒体播放前台通知需要）
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // 返回键：与右滑手势一致，回到上一页
    BackHandler(enabled = nav.screen != Screen.Home) {
        nav.screen = nav.backTarget(nav.screen)
    }

    // 播放状态：全局实况胶囊依赖（有歌曲时在所有页面底部悬浮，协议页不经过此处）
    val now by ServiceLocator.player.state.collectAsStateWithLifecycle()

    // 低配置模式：页面转场直接硬切（省 GPU 合成与动画帧）
    val lowPerf = LocalLowPerf.current
    // 屏幕形状：方表用物理黑边承担边界（悬浮元素贴屏）
    val isRoundScreen = LocalIsRoundScreen.current
    Box(Modifier.fillMaxSize()) {
        AppNavHost(nav = nav, now = now, lowPerf = lowPerf, navScope = navScope)

        // ---- 实况胶囊：全局悬浮常驻屏幕底部（协议页/播放页/歌词页除外），点击进播放页 ----
        // 歌词页不显示：胶囊会压住底部歌词行，且歌名/进播放页功能与页面自身内容重复
        if (now.song != null && nav.screen != Screen.Player && nav.screen != Screen.Lyrics) {
            LiveCapsule(
                coverUrl = now.song?.cover300.orEmpty(),
                songName = now.song?.name.orEmpty(),
                onClick = { nav.screen = Screen.Player },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // 圆表：底部留白避开弧边；方表：物理黑边承担边界，贴屏显示
                    .padding(bottom = if (isRoundScreen) 24.dp else 6.dp),
            )
        }
    }
}