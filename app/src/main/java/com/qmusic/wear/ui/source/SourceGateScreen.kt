package com.qmusic.wear.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.data.source.MusicSource
import com.qmusic.wear.data.source.SourceRegistry
import com.qmusic.wear.data.source.SourceState
import com.qmusic.wear.ui.components.PageTitle
import com.qmusic.wear.ui.components.QmScreenScaffold
import com.qmusic.wear.ui.components.edgeScalingParams
import com.qmusic.wear.ui.components.qmAutoCentering
import com.qmusic.wear.ui.components.qmRotarySnap
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/**
 * 音乐源门页（同意协议后、源就绪前展示）：
 * - 列出全部可选音乐源，用户点选其一（首启默认 QQ 音乐）
 * - 选中后自动从镜像下载对应源脚本；失败可重试，或「从存储导入」本地已签名的源文件兜底
 * - 协议实现不在 APK 内；就绪后由宿主进入主页
 */
@Composable
fun SourceGateScreen(
    state: SourceState,
    activeId: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    onImport: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    QmScreenScaffold(
        scrollState = listState,
        timeText = { TimeText() },
    ) { contentPadding ->
        ScalingLazyColumn(
            scalingParams = edgeScalingParams(),
            state = listState,
            rotaryScrollableBehavior = qmRotarySnap(listState),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding(),
                bottom = contentPadding.calculateBottomPadding() + 12.dp,
            ),
            autoCentering = qmAutoCentering(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { PageTitle("选择音乐源") }
            item {
                Text(
                    "一次只加载一个源，切换即切换曲库与账号",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }

            items(SourceRegistry.sources, key = { it.id }) { src ->
                GateSourceRow(
                    source = src,
                    selected = src.id == activeId,
                    onClick = { onSelect(src.id) },
                )
            }

            // ---- 当前源加载状态 ----
            item {
                Spacer(Modifier.height(4.dp))
                when (state) {
                    is SourceState.Downloading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "正在下载音乐源…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is SourceState.Missing -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                "准备获取音乐源…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    is SourceState.Failed -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                state.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(
                                    horizontal = if (LocalIsRoundScreen.current) 24.dp else 2.dp,
                                ),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = onRetry) {
                                    Text("重试", style = MaterialTheme.typography.labelMedium)
                                }
                                OutlinedButton(onClick = onImport) {
                                    Text("从存储导入", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }

                    is SourceState.Ready -> Unit
                }
            }
        }
    }
}

/** 门页音乐源行：品牌色圆点 + 名称 + 说明 + 选中圆点 */
@Composable
private fun GateSourceRow(
    source: MusicSource,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    val isRound = LocalIsRoundScreen.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isRound) 14.dp else 2.dp)
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHigh
                else MaterialTheme.colorScheme.surfaceContainer,
            )
            .border(
                0.5.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                else Color.White.copy(alpha = 0.10f),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(Color(source.themeColor)),
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                source.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.subtitle.ifEmpty { source.id },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    ),
            )
        }
    }
}