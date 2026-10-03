package com.qmusic.wear.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.qmusic.wear.R
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/**
 * 搜索栏（歌曲/歌手/歌单）+ 右侧语音入口：
 * 麦克风加大为深色圆底按钮（此前灰色裸图标过小、难以发现和点中）。
 */
@Composable
internal fun MineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onVoiceSearch: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 圆表顶部落在弧区（可用宽度最窄处），搜索框整条收薄；方表数值一律不变
    val isRound = LocalIsRoundScreen.current
    val hPad = if (isRound) 10.dp else 12.dp
    val vPad = if (isRound) 5.dp else 9.dp
    val iconBox = if (isRound) 22.dp else 26.dp
    val iconSize = if (isRound) 14.dp else 16.dp
    val micBox = if (isRound) 26.dp else 34.dp
    val micSize = if (isRound) 14.dp else 18.dp
    val gap = if (isRound) 3.dp else 4.dp
    // 圆表输入字号同步收一档，避免文字把矮搜索框撑回原高度
    val inputFontSize = if (isRound) {
        MaterialTheme.typography.labelLarge.fontSize
    } else {
        MaterialTheme.typography.bodyMedium.fontSize
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = inputFontSize,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
            .border(0.5.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
            .padding(horizontal = hPad, vertical = vPad),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 放大镜兼作确认按钮：点它等同按键盘搜索键
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(iconBox)
                        .clip(CircleShape)
                        .clickable(onClick = onSearch),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = "搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(iconSize),
                    )
                }
                Spacer(Modifier.size(gap))
                if (query.isEmpty()) {
                    // 圆表：提示文字降一档 + 文案收短，避免占满矮框后顶到右侧麦克风
                    Text(
                        if (isRound) "搜索歌曲/歌手" else "搜索歌曲/歌手/歌单",
                        style = if (isRound) {
                            MaterialTheme.typography.labelSmall
                        } else {
                            MaterialTheme.typography.labelMedium
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 文本编辑区占剩余宽度，麦克风固定在右侧
                Box(Modifier.weight(1f)) { inner() }
                // 语音搜索：深色圆底 + 图标（命中区与视觉一致）；圆表收薄
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(micBox)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.40f))
                        .border(0.5.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                        .clickable(onClick = onVoiceSearch),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_mic),
                        contentDescription = "语音搜索",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(micSize),
                    )
                }
            }
        },
    )
}

/** 搜索历史：标题行（含清除）+ 换行排布的关键词胶囊 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun SearchHistoryRow(
    history: List<String>,
    onPick: (String) -> Unit,
    onClear: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "最近搜索",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "清除",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 外扩命中区（视觉不变）
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClear() }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            history.take(6).forEach { h ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.42f))
                        .clickable { onPick(h) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        h,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}