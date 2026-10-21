package com.qmusic.wear.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.R
import com.qmusic.wear.data.auth.LoginType
import com.qmusic.wear.ui.components.GlassPanel
import com.qmusic.wear.ui.components.edgeToEdgeContentPadding
import com.qmusic.wear.ui.login.LoginUiState.QrStatus
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

@Composable
fun LoginScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // 当前音乐源展示名（登录页文案与二维码同源切换）
    val sourceName by com.qmusic.wear.data.source.SourceManager.displayNameFlow.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.startLogin() }
    // 退出组合（返回上级/跳走）时主动收场：VM 是 Activity 作用域，onCleared 此时不会触发，
    // 残留的轮询会占住单线程源引擎，把之后的接口调用全排在它后面
    DisposableEffect(Unit) {
        onDispose { vm.abandon() }
    }
    LaunchedEffect(ui.status) {
        if (ui.status == QrStatus.Success) {
            kotlinx.coroutines.delay(600)
            onSuccess()
        }
    }

    ScreenScaffold(
        timeText = { TimeText() },
    ) { contentPadding ->
        // 屏幕形状：圆表两侧留防弧边留白，方表收窄给二维码/文案更多宽度
        val hPad = if (LocalIsRoundScreen.current) 18.dp else 2.dp
        Column(
            Modifier
                .fillMaxSize()
                .padding(edgeToEdgeContentPadding(contentPadding))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = hPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.login_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(5.dp))

            // 扫码方式切换：仅当源实现了 wxLogin 才出现（能力探测门控）
            if (ui.wxAvailable) {
                LoginTypeChips(selected = ui.type, onSelect = { vm.selectType(it) })
                Spacer(Modifier.height(6.dp))
            }

            when (ui.status) {
                QrStatus.Loading, QrStatus.Ready, QrStatus.WaitingScan, QrStatus.ScannedConfirm -> {
                    // 居中显示：GlassPanel 内部默认靠左，这里显式居中避免二维码偏移
                    GlassPanel(horizontalAlignment = Alignment.CenterHorizontally) {
                        val bytes = ui.qrBytes
                        if (bytes != null) {
                            val decoded = runCatching {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }.getOrNull() ?: android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                            Image(
                                bitmap = decoded.asImageBitmap(),
                                contentDescription = "$sourceName 登录二维码",
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White)
                                    .padding(4.dp),
                            )
                        } else {
                            Box(
                                Modifier.size(96.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.wear.compose.material3.CircularProgressIndicator()
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        // 扫码提示随方式切换：微信用「扫一扫」，QQ 用手机 QQ
                        val scanHint = when (ui.type) {
                            LoginType.WECHAT -> stringResource(R.string.login_scan_wx)
                            else -> stringResource(R.string.login_scan_qq, sourceName)
                        }
                        Text(
                            text = when (ui.status) {
                                QrStatus.ScannedConfirm -> stringResource(R.string.login_scanned)
                                else -> scanHint
                            },
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                        )
                        // 微信与 QQ 是两个独立账号体系，扫码前先讲清资产不互通
                        if (ui.type == LoginType.WECHAT) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = stringResource(R.string.login_wx_tip),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                QrStatus.Expired, QrStatus.Refused, QrStatus.Error -> {
                    Text(
                        text = ui.message.ifEmpty { stringResource(R.string.login_expired) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.retry() }) {
                        Text(stringResource(R.string.cd_refresh))
                    }
                }
                QrStatus.Success -> {
                    Text(
                        text = stringResource(R.string.login_success),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(5.dp))
            androidx.wear.compose.material3.Button(
                onClick = onBack,
                modifier = Modifier.padding(bottom = 2.dp),
                colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Text(stringResource(R.string.login_guest), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** 扫码方式胶囊行：QQ / 微信（沿用设置页选项卡样式，选中填主色） */
@Composable
private fun LoginTypeChips(selected: LoginType, onSelect: (LoginType) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            LoginType.QQ to stringResource(R.string.login_type_qq),
            LoginType.WECHAT to stringResource(R.string.login_type_wx),
        ).forEach { (type, label) ->
            val isSelected = selected == type
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                    )
                    .clickable { onSelect(type) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}
