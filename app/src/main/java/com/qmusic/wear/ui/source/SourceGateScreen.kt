package com.qmusic.wear.ui.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.data.source.SourceState
import com.qmusic.wear.ui.components.PageTitle

/**
 * 音乐源下载门页（同意协议后、源就绪前展示）：
 * - 首启自动从镜像下载音乐源插件（协议实现不在 APK 内）
 * - 失败可重试，或「从存储导入」本地已签名的源文件兜底；就绪后由宿主进入主页
 */
@Composable
fun SourceGateScreen(
    state: SourceState,
    onRetry: () -> Unit,
    onImport: () -> Unit,
) {
    ScreenScaffold(timeText = { TimeText() }) { contentPadding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            PageTitle("音乐源")
            Spacer(Modifier.height(8.dp))

            when (state) {
                is SourceState.Downloading -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "正在下载音乐源…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is SourceState.Failed -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = onRetry) {
                            Text("重试", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(onClick = onImport) {
                            Text("从存储导入", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }

                is SourceState.Missing -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "准备获取音乐源…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is SourceState.Ready -> Unit
            }
        }
    }
}
