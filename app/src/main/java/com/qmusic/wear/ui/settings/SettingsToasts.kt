package com.qmusic.wear.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * 设置页的三处结果提示：日志提取 / 音乐源更新 / 缓存清理。
 * 由 ViewModel 的瞬时消息字段驱动，提示后立即消费清空。
 */
@Composable
internal fun SettingsToasts(ui: SettingsUiState, vm: SettingsViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(ui.logExportMessage) {
        val msg = ui.logExportMessage
        if (!msg.isNullOrEmpty()) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.dismissLogExport()
        }
    }

    LaunchedEffect(ui.sourceUpdateMessage) {
        val msg = ui.sourceUpdateMessage
        if (!msg.isNullOrEmpty()) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.dismissSourceUpdate()
        }
    }

    LaunchedEffect(ui.cacheClearMessage) {
        val msg = ui.cacheClearMessage
        if (!msg.isNullOrEmpty()) {
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            vm.dismissCacheClear()
        }
    }
}