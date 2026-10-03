package com.qmusic.wear.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScreenScaffoldDefaults
import androidx.wear.compose.material3.TimeText
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

// -------------------------------------------------------------------------
// 方表脚手架与滚动指示
// -------------------------------------------------------------------------

/** 方表首部距屏幕顶端的留白：库默认竖直留白为屏高的 10%（方表约 22dp），首部会明显偏下 */
internal val SquareTopContentPadding = 12.dp

/**
 * 方表贴边适配：material3 [ScreenScaffold] 默认 contentPadding 自带屏宽 5.2% 的横向分量
 * （方表 ≈13dp），与页面自身的 2dp 贴边留白叠加成「卡片与屏幕边缘之间的双层空隙」。
 * 方表去掉横向分量（边界完全由物理黑边承担），圆表原样返回（圆表取值不变）。
 *
 * 方表同时把顶部留白收敛到 [SquareTopContentPadding]，配合 [qmAutoCentering] 关闭首项居中后，
 * 页面首部才能真正贴到屏幕顶端；圆表保留库默认（首项居中，弧面视口惯例），不受影响。
 */
@Composable
fun edgeToEdgeContentPadding(contentPadding: PaddingValues): PaddingValues =
    if (LocalIsRoundScreen.current) {
        contentPadding
    } else {
        PaddingValues(
            top = contentPadding.calculateTopPadding().coerceAtMost(SquareTopContentPadding),
            bottom = contentPadding.calculateBottomPadding(),
        )
    }

/**
 * 屏幕形状感知的页面脚手架：
 * - 圆表：与 material3 [ScreenScaffold] 完全一致（默认弧形滚动指示）。
 * - 方表：禁用弧形指示条，改用右侧竖直细条 [SquareScrollIndicator]（表冠处，中间略偏上）；
 *   并去掉默认 contentPadding 的横向分量，卡片贴边不留空隙。
 */
@Composable
fun QmScreenScaffold(
    scrollState: ScalingLazyListState,
    modifier: Modifier = Modifier,
    timeText: @Composable () -> Unit = { TimeText() },
    // 全屏覆盖页（如「我的」）打开时置 false，避免与本页指示条在同一位置叠画两根
    showScrollIndicator: Boolean = true,
    content: @Composable BoxScope.(PaddingValues) -> Unit,
) {
    val isRound = LocalIsRoundScreen.current
    if (isRound) {
        ScreenScaffold(
            scrollState = scrollState,
            modifier = modifier,
            timeText = timeText,
            content = content,
        )
    } else {
        // 方表：指示条不再经 scrollIndicator slot——该 slot 内的 fullscreen Box（内容每帧随
        // layoutInfo 重算重组）会插入 content 与列表之间，破坏 ScalingLazyColumn 内置的
        // requestFocusOnHierarchyActive rotary 焦点链；改为外层 Box 直接叠加指示条。
        // contentPadding 预裁横向分量后透传（与原 wrapper 等价），content 原样直传，
        // 节点结构与已验证表冠有效的原生 ScreenScaffold 路径一致。
        Box(modifier) {
            ScreenScaffold(
                scrollState = scrollState,
                modifier = Modifier.fillMaxSize(),
                timeText = timeText,
                contentPadding = edgeToEdgeContentPadding(ScreenScaffoldDefaults.contentPadding),
                scrollIndicator = null,
                content = content,
            )
            if (showScrollIndicator) {
                SquareScrollIndicator(
                    scrollState,
                    Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 3.dp),
                )
            }
        }
    }
}

/**
 * 方表专用滚动指示条：右侧竖直圆角细条，随列表进度上下移动。
 * 轨道中心比屏幕几何中心略偏上（对齐表冠位置）。
 */
@Composable
fun SquareScrollIndicator(state: ScalingLazyListState, modifier: Modifier = Modifier) {
    val progress by remember {
        derivedStateOf {
            val li = state.layoutInfo
            val total = li.totalItemsCount
            if (total <= 1) return@derivedStateOf -1f
            val vis = li.visibleItemsInfo
            val denom = (total - vis.size).coerceAtLeast(1)
            // 首个可见项 index + 项内滚动比例（offset 为负表示滚出顶部），换算成小数进度
            val first = vis.firstOrNull()
            val itemSize = (first?.size ?: 0).coerceAtLeast(1)
            val idxF = (first?.index ?: 0) - (first?.offset ?: 0).toFloat() / itemSize
            (idxF / denom).coerceIn(0f, 1f)
        }
    }
    if (progress < 0f) return
    // 轨道：灰底全程可见（同圆表指示条风格），滑块 24dp 在 36dp 行程内随进度移动；
    // 整体中心比几何中心偏上 40dp（表冠上方区域）
    val travel = 36.dp
    val yAnim by animateDpAsState(travel * progress, label = "sq_scroll_ind")
    Box(
        modifier
            .offset(y = -40.dp)
            .size(width = 3.dp, height = travel + 24.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            Modifier
                .offset(y = yAnim)
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.55f)),
        )
    }
}