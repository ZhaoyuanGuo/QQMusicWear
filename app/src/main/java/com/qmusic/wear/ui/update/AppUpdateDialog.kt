package com.qmusic.wear.ui.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.data.update.AppUpdateState
import com.qmusic.wear.data.update.UpdateInfo
import com.qmusic.wear.util.formatBytes

/**
 * 应用更新全局弹窗：由 [AppUpdateState] 状态机驱动，展示新版本说明/下载进度/安装入口。
 * forced（低于服务端 minVersionCode）时隐藏所有「以后再说」出口，只允许更新或复制链接。
 * 按钮语义随状态切换：Available 立即下载 → Downloading 进度 → ReadyToInstall 安装 → Failed 重试。
 */
@Composable
internal fun AppUpdateDialog(
    state: AppUpdateState,
    localVersionName: String,
    forced: Boolean,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCopyLink: () -> Unit,
    onDismiss: () -> Unit,
) {
    val info: UpdateInfo? = when (state) {
        is AppUpdateState.Available -> state.info
        is AppUpdateState.Downloading -> state.info
        is AppUpdateState.ReadyToInstall -> state.info
        is AppUpdateState.Failed -> state.info
        else -> null
    }
    if (info == null) return

    val title = when (state) {
        is AppUpdateState.Failed -> "更新失败"
        is AppUpdateState.ReadyToInstall -> "下载完成"
        else -> "发现新版本 v${info.versionName.ifEmpty { info.versionCode.toString() }}"
    }

    AlertDialog(
        visible = true,
        onDismissRequest = { if (!forced) onDismiss() },
        title = { Text(title) },
        text = {
            // 更新说明可能较长，内容区可滚动防裁切（与 QualityPicker 同思路）
            Column(Modifier.verticalScroll(rememberScrollState())) {
                when (state) {
                    is AppUpdateState.Downloading -> Text(
                        if (state.progress >= 0f) "下载中 ${state.percent()}%" else "下载中…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is AppUpdateState.ReadyToInstall -> Text(
                        "安装包已下载并校验，点击「安装」调起系统安装器",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    is AppUpdateState.Failed -> Text(
                        state.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
                if (info.notes.isNotEmpty() && state !is AppUpdateState.Failed) {
                    Text(
                        info.notes,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val extras = buildString {
                    if (info.sizeBytes > 0) append("安装包 ${info.sizeBytes.formatBytes()}")
                    if (localVersionName.isNotEmpty()) {
                        if (isNotEmpty()) append(" · ")
                        append("当前 v$localVersionName")
                    }
                }
                if (extras.isNotEmpty()) {
                    Text(
                        extras,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            when (state) {
                is AppUpdateState.Available -> Button(onClick = onDownload) { Text("立即下载") }
                is AppUpdateState.Downloading -> Button(onClick = {}, enabled = false) {
                    Text(
                        if (state.progress >= 0f) "${state.percent()}%" else "下载中",
                        maxLines = 1,
                    )
                }
                is AppUpdateState.ReadyToInstall -> Button(onClick = onInstall) { Text("安装") }
                is AppUpdateState.Failed -> Button(onClick = onDownload) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = {
            when {
                // 强制更新不提供任何跳过出口
                forced -> Unit
                state is AppUpdateState.Available -> Button(onClick = onDismiss) { Text("以后再说") }
                state is AppUpdateState.Downloading -> Button(onClick = onDismiss) { Text("取消") }
                else -> Button(onClick = onCopyLink) { Text("复制链接") }
            }
        },
    )
}

private fun AppUpdateState.Downloading.percent(): Int =
    (progress * 100).toInt().coerceIn(0, 100)
