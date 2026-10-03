package com.qmusic.wear.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.qmusic.wear.R
import com.qmusic.wear.data.model.UserProfile
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.components.GlassRow
import com.qmusic.wear.ui.components.SectionHeader

/**
 * 「通用」选项卡内容：账号与登录 / QPlay 投放 / 存储 / 音乐源 / 日志 / 开屏提示 / 关于。
 * 以 [ScalingLazyListScope] 扩展函数形式注入，避免主页面单函数过长。
 */
internal fun ScalingLazyListScope.settingsGeneralTab(
    ui: SettingsUiState,
    profile: UserProfile?,
    vm: SettingsViewModel,
    onOpenLogin: () -> Unit,
) {
    item { SectionHeader("账号与登录") }
    item { AccountCard(profile, ui.activeSourceName, onOpenLogin) }
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

    settingsQPlaySection(ui, vm)
    settingsStorageSection(ui, vm)
    settingsMiscSection(ui, vm)
}

/** 账号卡：已登录显示头像/昵称/来源账号/会员徽标，未登录显示「扫码登录」入口 */
@Composable
private fun AccountCard(
    profile: UserProfile?,
    activeSourceName: String,
    onOpenLogin: () -> Unit,
) {
    if (profile == null) {
        GlassRow(onClick = onOpenLogin) {
            Icon(
                painter = painterResource(R.drawable.ic_user),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text("登录账号", style = MaterialTheme.typography.labelLarge)
                Text(
                    "扫码登录同步歌单",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
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
                // 副标题行：来源账号 + 会员身份徽标（绿钻/概念版/黑胶等）。
                // 徽标放副标题行而非昵称同行——手表屏宽有限，同行会把昵称挤成「PUB…」
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${activeSourceName}账号",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (profile.vipLabel.isNotEmpty()) {
                        Spacer(Modifier.size(5.dp))
                        VipBadge(profile.vipLabel)
                    }
                }
            }
        }
    }
}

/** QPlay 投放开关：手表出现在手机 QQ 音乐的 QPlay 设备列表 */
private fun ScalingLazyListScope.settingsQPlaySection(ui: SettingsUiState, vm: SettingsViewModel) {
    item { SectionHeader("QPlay 投放") }
    item {
        GlassRow(onClick = { vm.setQPlayEnabled(!ui.qplayEnabled) }) {
            Icon(
                painter = painterResource(R.drawable.ic_volume),
                contentDescription = null,
                tint = if (ui.qplayEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "接收手机投放",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (ui.qplayEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (ui.qplayEnabled) "手机 QQ 音乐 QPlay 可发现本手表"
                    else "开启后出现在手机 QPlay 设备列表",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TogglePill(checked = ui.qplayEnabled)
        }
    }
}

/** 日志提取 / 开屏提示 / 关于 */
private fun ScalingLazyListScope.settingsMiscSection(ui: SettingsUiState, vm: SettingsViewModel) {
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

    // ---- 开屏提示开关 ----
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
                    "仅供学习交流提示",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TogglePill(checked = ui.launchToastEnabled)
        }
    }

    // ---- 关于 ----
    item { SectionHeader("关于") }
    item {
        val ctx = LocalContext.current
        val versionName = remember {
            runCatching {
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            }.getOrNull()
        }
        GlassPanel {
            Column(Modifier.fillMaxWidth()) {
                Text("QQMusicWear", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.size(3.dp))
                Text(
                    "版本 ${versionName ?: "-"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(2.dp))
                Text(
                    "仅供学习交流使用，请于24小时内删除",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}