package com.qmusic.wear.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.Quality
import com.qmusic.wear.data.player.SleepTimer
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.SectionHeader
import com.qmusic.wear.ui.components.rotaryList
import com.qmusic.wear.util.msTo_mmss

/**
 * 设置页（“我的”页二级界面）：
 * - 账号与登录：未登录显示登录入口，已登录显示头像昵称 + 退出登录
 * - 播放音质 / 下载音质（相互独立）
 * - 睡眠定时：到时自动暂停
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

    LaunchedEffect(Unit) { vm.load() }

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

    // 日志提取结果：Toast 提示导出路径
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(ui.logExportMessage) {
        val msg = ui.logExportMessage
        if (!msg.isNullOrEmpty()) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.dismissLogExport()
        }
    }

    // 音乐源更新结果：Toast 提示
    LaunchedEffect(ui.sourceUpdateMessage) {
        val msg = ui.sourceUpdateMessage
        if (!msg.isNullOrEmpty()) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.dismissSourceUpdate()
        }
    }

    ScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().rotaryList(listState),
        ) {
            item { PageTitle("设置") }

            // ---- 账号与登录 ----
            item { SectionHeader("账号与登录") }
            item {
                if (profile != null) {
                    GlassPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 1.5.dp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                        shape = CircleShape,
                                    )
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (profile.avatarUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = profile.avatarUrl,
                                        contentDescription = "头像",
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier
                                            .size(43.dp)
                                            .clip(CircleShape),
                                    )
                                } else {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_user),
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = profile.nick.ifEmpty { "已登录" },
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "QQ音乐账号",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    GlassRow(onClick = onOpenLogin) {
                        Icon(
                            painter = painterResource(R.drawable.ic_user),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "登录账号",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                "扫码登录，同步我喜欢与歌单",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (profile != null) {
                item {
                    GlassRow(onClick = { vm.requestLogout() }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_logout),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "退出登录",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ---- 播放音质 ----
            item { SectionHeader("播放音质") }
            items(Quality.entries.size) { idx ->
                val q = Quality.entries[idx]
                QualityRow(
                    q = q,
                    selected = q == ui.quality,
                    onSelect = { vm.selectQuality(q) },
                )
            }

            // ---- 下载音质（独立于播放音质） ----
            item { SectionHeader("下载音质") }
            items(Quality.entries.size) { idx ->
                val q = Quality.entries[idx]
                QualityRow(
                    q = q,
                    selected = q == ui.downloadQuality,
                    onSelect = { vm.selectDownloadQuality(q) },
                )
            }

            // ---- 睡眠定时 ----
            item {
                SectionHeader(
                    if (sleepRemaining > 0) {
                        "睡眠定时 · 剩余 ${(sleepRemaining * 1000L).msTo_mmss()}"
                    } else {
                        "睡眠定时"
                    },
                )
            }
            val sleepOptions = listOf(0 to "关闭", 15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟")
            items(sleepOptions.size) { idx ->
                val (minutes, label) = sleepOptions[idx]
                OptionRow(
                    label = label,
                    selected = ui.sleepMinutes == minutes && (minutes != 0 || sleepRemaining <= 0L),
                    onClick = { vm.selectSleepTimer(minutes) },
                )
            }

            // ---- 音乐源 ----
            item { SectionHeader("音乐源") }
            item {
                GlassRow(onClick = { vm.updateSource() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            // null=空闲显示「更新音乐源」；""=更新中/结果提示待消失显示「更新中…」
                            if (ui.sourceUpdateMessage != null) "更新中…" else "更新音乐源",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            if (ui.sourceVersion > 0) "当前版本 v${ui.sourceVersion}" else "未加载",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item {
                val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri != null) {
                        val bytes = runCatching {
                            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        }.getOrNull()
                        if (bytes != null) vm.importSource(bytes)
                    }
                }
                GlassRow(onClick = { importLauncher.launch(arrayOf("*/*")) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("从存储导入源", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "镜像全部失效时的兜底",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ---- 提取日志 ----
            item {
                GlassRow(onClick = { vm.extractLog() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_download),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (ui.logExportMessage == "") "提取中…" else "提取日志（诊断）",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            // ---- 通用：开屏提示开关 ----
            item {
                GlassRow(onClick = { vm.setLaunchToast(!ui.launchToastEnabled) }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_volume),
                        contentDescription = null,
                        tint = if (ui.launchToastEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "开屏提示",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (ui.launchToastEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "启动时显示「仅供学习交流使用」",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TogglePill(checked = ui.launchToastEnabled)
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** 开关胶囊：小型 iOS 风格 toggle 指示器（选中绿底右浮点，未选中灰底左浮点） */
@Composable
private fun TogglePill(checked: Boolean) {
    Box(
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        modifier = Modifier
            .size(width = 26.dp, height = 16.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(
                if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            .padding(horizontal = 2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color.White),
        )
    }
}

/** 音质选择行：图标 + 名称 + 码率说明 + 选中圆点 */
@Composable
private fun QualityRow(q: Quality, selected: Boolean, onSelect: () -> Unit) {
    OptionRow(
        label = q.label,
        subtitle = when (q) {
            Quality.STANDARD -> "128kbps"
            Quality.HIGH -> "320kbps"
            Quality.LOSSLESS -> "FLAC 无损"
            Quality.HI_RES -> "Hi-Res 至高"
        },
        selected = selected,
        onClick = onSelect,
        iconRes = R.drawable.ic_quality,
    )
}

/** 通用单选行：可选图标 + 名称 + 副标题 + 选中圆点指示 */
@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
    iconRes: Int? = null,
) {
    GlassRow(onClick = onClick) {
        if (iconRes != null) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 选中状态指示：外圈 + 选中实心圆点
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(18.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .border(
                        width = 2.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary
                        else Color.Transparent,
                    ),
            )
        }
    }
}
