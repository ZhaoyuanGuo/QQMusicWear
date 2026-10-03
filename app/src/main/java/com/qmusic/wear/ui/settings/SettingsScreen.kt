package com.qmusic.wear.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.data.model.defaultProgressStyle
import com.qmusic.wear.data.player.SleepTimer
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/**
 * 设置页（“我的”页二级界面）编排层：三选项卡拆分避免单列过长。
 * 具体内容按选项卡拆到 [settingsGeneralTab] / [settingsDisplayTab] / [settingsPlaybackTab]。
 */
@Composable
fun SettingsScreen(
    onOpenLogin: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.profile
    val listState = rememberScalingLazyListState()
    val sleepRemaining by SleepTimer.remainingSec.collectAsStateWithLifecycle()
    // 选项卡：0 通用 / 1 显示 / 2 播放设置（显示排在播放设置前）
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 生效的进度样式：显式选择优先，未选择时按当前屏幕形态取默认值
    val resolvedProgressStyle = ui.progressStyle ?: defaultProgressStyle(LocalIsRoundScreen.current)

    LaunchedEffect(Unit) { vm.load() }
    // 切换选项卡时回到列表顶部，避免停留在上一页的滚动位置
    LaunchedEffect(tab) { runCatching { listState.scrollToItem(0) } }

    if (ui.showLogoutConfirm) {
        AlertDialog(
            visible = true,
            onDismissRequest = { vm.cancelLogout() },
            title = { Text("退出登录") },
            text = { Text("确定要退出当前账号吗？") },
            confirmButton = {
                Button(onClick = { vm.confirmLogout() }) { Text("退出") }
            },
            dismissButton = {
                Button(onClick = { vm.cancelLogout() }) { Text("取消") }
            },
        )
    }

    // 日志提取 / 音乐源更新 / 缓存清理的结果 Toast
    SettingsToasts(ui = ui, vm = vm)

    QmScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = contentPadding,
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle("设置") }
            item { SettingsTabChips(selected = tab, onSelect = { tab = it }) }

            when (tab) {
                0 -> settingsGeneralTab(ui, profile, vm, onOpenLogin)
                1 -> settingsDisplayTab(ui, vm, resolvedProgressStyle)
                else -> settingsPlaybackTab(ui, vm, sleepRemaining)
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}