package com.qmusic.wear.ui.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Playlist
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.edgeListPadding
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmRotarySnap

/**
 * 「我的」页主体：页头/搜索栏/历史 → 登录状态提示 → 内容行（我的喜欢/统计/最近/…）。
 * 内容行分别由 [mineLibraryRows] / [mineEntryRows] 注入。
 */
@Composable
internal fun MineTabs(
    listState: ScalingLazyListState,
    ui: MineUiState,
    recentCount: Int,
    sessionBad: Boolean,
    logged: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    onSearch: () -> Unit,
    history: List<String>,
    onPickHistory: (String) -> Unit,
    onOpenLiked: (Playlist) -> Unit,
    onOpenRecent: () -> Unit,
    onOpenPlaylist: (Long, String) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPodcast: () -> Unit,
    onOpenSocial: () -> Unit,
) {
    // 听歌统计（本地数据）
    val stats = ServiceLocator.playStats.weekStats.collectAsStateWithLifecycle().value
    // 能力探测：源没实现的能力连入口都不显示（酷狗/QQ 无播客电台、无动态关注）
    val caps by com.qmusic.wear.data.source.SourceManager.capabilitiesFlow.collectAsStateWithLifecycle()
    val supportsPodcast = caps.contains("djRadios")
    val supportsSocial = caps.contains("userEvents") || caps.contains("userFollows")

    // ScalingLazyColumn：圆屏自适应缩放 + 与首页一致的滚动体验；
    // 表冠滚动走内置支持（外挂 rotaryScrollable 会与内置焦点协调抢焦点，已废弃）。
    // 关闭居中锚点（必须显式传 null：ScalingLazyColumn 默认就是 AutoCenteringParams()）：
    // 该列表自带页头并在固定区域内滚动，居中锚点会把首项下压、在顶部留出一条遮挡内容的
    // 黑色死区（内容无法滚到屏幕最顶），故方/圆表都不用。
    ScalingLazyColumn(
        Modifier.fillMaxWidth(),
        rotaryScrollableBehavior = qmRotarySnap(listState),
        contentPadding = edgeListPadding(),
        autoCentering = null,
        scalingParams = edgeScalingParams(),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        mineListHeader(query, onQueryChange, onVoiceSearch, onSearch, history, onPickHistory)
        mineStatusRows(sessionBad, logged, onOpenSettings)
        mineLibraryRows(
            ui = ui,
            stats = stats,
            recentCount = recentCount,
            logged = logged,
            supportsPodcast = supportsPodcast,
            supportsSocial = supportsSocial,
            onOpenLiked = onOpenLiked,
            onOpenRecent = onOpenRecent,
            onOpenPodcast = onOpenPodcast,
            onOpenSocial = onOpenSocial,
        )
        mineEntryRows(ui, onOpenPlaylist, onOpenDownloads, onOpenSettings)
        item { Spacer(Modifier.height(4.dp)) }
    }
}

/** 页头 + 搜索栏 + 搜索历史（随列表滚动：往下滑即跟随上移，不再悬浮占位） */
private fun ScalingLazyListScope.mineListHeader(
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    onSearch: () -> Unit,
    history: List<String>,
    onPickHistory: (String) -> Unit,
) {
    item {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            PageTitle("我的")
            Spacer(Modifier.height(8.dp))
        }
    }
    item {
        MineSearchField(
            query = query,
            onQueryChange = onQueryChange,
            onVoiceSearch = onVoiceSearch,
            onSearch = onSearch,
        )
    }
    if (history.isNotEmpty()) {
        item {
            SearchHistoryRow(
                history = history,
                onPick = onPickHistory,
                onClear = { ServiceLocator.searchHistory.clear() },
            )
        }
    }
}

/** 登录状态异常 / 未登录提示行（入口都指向设置页登录） */
private fun ScalingLazyListScope.mineStatusRows(
    sessionBad: Boolean,
    logged: Boolean,
    onOpenSettings: () -> Unit,
) {
    // 登录状态异常：凭据过期或接口全部拉取失败
    if (sessionBad) {
        item {
            GlassRow(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_user),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "登录可能已过期",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        "点击去设置重新扫码登录",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // 未登录提示：登录入口在设置里
    // （按本地凭据判断而非 profile 加载结果——profile 接口失败时账号仍是登录态，不能误报）
    if (!logged) {
        item {
            GlassRow(onClick = onOpenSettings) {
                Icon(
                    painter = painterResource(R.drawable.ic_user),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    "未登录 · 去设置登录",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}