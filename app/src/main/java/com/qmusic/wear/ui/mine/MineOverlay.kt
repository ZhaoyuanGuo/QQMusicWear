package com.qmusic.wear.ui.mine

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.hierarchicalFocusGroup
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.SearchResult
import com.qmusic.wear.ui.components.SquareScrollIndicator
import com.qmusic.wear.ui.components.edgeListHorizontalInset
import com.qmusic.wear.ui.components.rememberHaptics
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/**
 * 「我的」页（每日推荐页右滑出现）：
 * 顶部搜索栏（歌曲/歌手/歌单） + 我的喜欢 / 最近播放 / 我的歌单 / 下载管理 / 设置。
 * 输入完按键盘搜索键（或点放大镜）才发起搜索，避免拼音候选上屏被打断；左滑/右滑关闭（左滑=返回上一级）。
 *
 * 列表主体见 [MineTabs]，搜索结果见 [SearchResults]，搜索栏见 [MineSearchField]。
 */
@Composable
fun MineOverlay(
    onDismiss: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenRecent: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenArtist: (String, String) -> Unit = { _, _ -> },
    onOpenAlbum: (String, String) -> Unit = { _, _ -> },
    onOpenPodcast: () -> Unit = {},
    onOpenSocial: () -> Unit = {},
    vm: MineViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val recent by ServiceLocator.historyStore.recentFlow.collectAsStateWithLifecycle(
        initialValue = emptyList(),
    )
    val history by ServiceLocator.searchHistory.historyFlow.collectAsStateWithLifecycle()
    val cred by ServiceLocator.credential.collectAsStateWithLifecycle()
    // 凭据到期或接口全挂 → 顶部提示重新登录
    val sessionBad = (cred.isLogged && cred.isExpired) || ui.suspectSession
    var swipeAcc by remember { mutableFloatStateOf(0f) }
    val haptics = rememberHaptics()

    LaunchedEffect(Unit) { vm.load() }

    // 语音搜索：系统语音识别 → 识别结果填入搜索框自动触发搜索
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            vm.submitSearch(text)
        }
    }
    val launchVoice: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要搜索的歌曲、歌手或歌单")
        }
        // 设备无语音识别组件时静默忽略
        runCatching { voiceLauncher.launch(intent) }
    }
    // 输入态标记：区分"正在打字"与"点历史词/语音结果"，用于切到结果页时恢复焦点与键盘
    var typing by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // 列表状态提升到顶层：指示条/刻度振动需要跟随当前活跃列表（主列表 或 搜索结果）
    val mineListState = rememberScalingLazyListState()
    val searchListState = rememberScalingLazyListState()
    val isRoundScreen = LocalIsRoundScreen.current
    // 搜索结果页：搜索框/标签条与上下内容之间的间距（圆表顶部弧区窄，收一档）
    val headerGap = if (isRoundScreen) 4.dp else 6.dp
    // 固定头部与下方结果列表同宽（复用列表的横向防弧边内缩）
    val headerInset = edgeListHorizontalInset()

    Box(
        Modifier
            .fillMaxSize()
            // 焦点组：与下层首页列表互斥持焦（打开时拿走表冠焦点，关闭时让首页组重新拿回）
            .hierarchicalFocusGroup(active = true)
            // 纯黑底（OLED 不发光）：必须全不透明，否则下层页面的模糊封面会透出来
            .background(MaterialTheme.colorScheme.background)
            // 圆表两侧防弧边留白；方表收窄用满 245dp 级别的窄宽度
            .padding(horizontal = if (isRoundScreen) 12.dp else 2.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        swipeAcc += amount
                    },
                    onDragEnd = {
                        // 左滑：返回上一级（推荐页）；右滑：同样关闭
                        when {
                            swipeAcc < -70f -> {
                                haptics.confirm()
                                onDismiss()
                            }
                            swipeAcc > 70f -> {
                                haptics.confirm()
                                onDismiss()
                            }
                        }
                        swipeAcc = 0f
                    },
                )
            },
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                // 主列表：搜索栏与搜索历史放进列表，随内容滚动（往下滑即跟随上移，不再悬浮）
                // 结果页不放页标题：省出顶部空间给结果列表
                search.query.isBlank() -> Box(Modifier.weight(1f)) {
                    MineTabs(
                        listState = mineListState,
                        ui = ui,
                        recentCount = recent.size,
                        sessionBad = sessionBad,
                        logged = cred.isLogged,
                        query = search.query,
                        onQueryChange = { typing = true; vm.onQueryChange(it) },
                        onVoiceSearch = launchVoice,
                        onSearch = { typing = true; vm.submitSearch() },
                        history = history,
                        onPickHistory = { typing = false; vm.submitSearch(it) },
                        onOpenLiked = { liked -> onOpenPlaylist(liked.disstid, liked.name) },
                        onOpenRecent = onOpenRecent,
                        onOpenPlaylist = onOpenPlaylist,
                        onOpenDownloads = onOpenDownloads,
                        onOpenSettings = onOpenSettings,
                        onOpenPodcast = onOpenPodcast,
                        onOpenSocial = onOpenSocial,
                    )
                }

                search.searching -> Box(Modifier.weight(1f)) {
                    SearchHeaderAndLoading(
                        query = search.query,
                        onQueryChange = { typing = true; vm.onQueryChange(it) },
                        onVoiceSearch = launchVoice,
                        onSearch = { typing = true; vm.submitSearch() },
                        fieldFocus = fieldFocus,
                        headerInset = headerInset,
                        headerGap = headerGap,
                        typing = typing,
                        onTypingConsumed = { typing = false },
                        keyboardShow = { keyboard?.show() },
                    )
                }

                else -> Box(Modifier.weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(headerGap))
                        MineSearchField(
                            query = search.query,
                            onQueryChange = { typing = true; vm.onQueryChange(it) },
                            onVoiceSearch = launchVoice,
                            onSearch = { typing = true; vm.submitSearch() },
                            modifier = Modifier
                                .focusRequester(fieldFocus)
                                .padding(horizontal = headerInset),
                        )
                        LaunchedEffect(Unit) {
                            // 从主列表打字切换过来时恢复焦点与键盘（搜索栏换了挂载点）
                            if (typing) {
                                fieldFocus.requestFocus()
                                keyboard?.show()
                                typing = false
                            }
                        }
                        Spacer(Modifier.height(headerGap))
                        SearchResults(
                            listState = searchListState,
                            result = search.result ?: SearchResult(),
                            onPlaySong = { song ->
                                // 点播加入队列：追加到当前队列尾部并播放该曲（其余歌曲不动）
                                ServiceLocator.player.enqueueAndPlay(song)
                                onDismiss()
                                onOpenPlayer()
                            },
                            onPlayAll = { songs ->
                                if (songs.isNotEmpty()) {
                                    vm.playFrom(songs, songs.first().mid)
                                    onDismiss()
                                    onOpenPlayer()
                                }
                            },
                            onOpenArtist = { singer ->
                                onDismiss()
                                onOpenArtist(singer.mid, singer.name)
                            },
                            onOpenAlbumOfSong = { song ->
                                if (song.albumMid.isNotEmpty()) {
                                    onDismiss()
                                    onOpenAlbum(song.albumMid, song.albumName)
                                }
                            },
                            onOpenPlaylist = { pl ->
                                onDismiss()
                                onOpenPlaylist(pl.disstid, pl.name)
                            },
                        )
                    }
                }
            }
        }

        // 方表右侧滚动指示条：跟随当前活跃列表（主列表 / 搜索结果；搜索中不显示）
        val activeListState = when {
            search.query.isBlank() -> mineListState
            search.searching -> null
            else -> searchListState
        }
        activeListState?.let { st ->
            if (!LocalIsRoundScreen.current) {
                SquareScrollIndicator(
                    st,
                    Modifier
                        .align(Alignment.CenterEnd)
                        // 根 Box 已有 2dp 横向 padding，补 1dp 使指示条与 QmScreenScaffold 对齐（距屏边 3dp）
                        .padding(end = 1.dp),
                )
            }
        }
    }
}

/** 搜索中：固定顶部搜索栏 + 居中加载圈（切换挂载点时按需恢复焦点与键盘） */
@Composable
private fun SearchHeaderAndLoading(
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    onSearch: () -> Unit,
    fieldFocus: FocusRequester,
    headerInset: androidx.compose.ui.unit.Dp,
    headerGap: androidx.compose.ui.unit.Dp,
    typing: Boolean,
    onTypingConsumed: () -> Unit,
    keyboardShow: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(headerGap))
        // 结果页搜索栏固定顶部，便于修改关键词
        MineSearchField(
            query = query,
            onQueryChange = onQueryChange,
            onVoiceSearch = onVoiceSearch,
            onSearch = onSearch,
            modifier = Modifier
                .focusRequester(fieldFocus)
                .padding(horizontal = headerInset),
        )
        LaunchedEffect(Unit) {
            if (typing) {
                fieldFocus.requestFocus()
                keyboardShow()
                onTypingConsumed()
            }
        }
        Spacer(Modifier.height(headerGap))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}