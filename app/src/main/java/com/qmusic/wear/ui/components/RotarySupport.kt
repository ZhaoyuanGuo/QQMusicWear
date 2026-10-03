package com.qmusic.wear.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.ScalingParams
import androidx.wear.compose.foundation.rotary.RotaryScrollableBehavior
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import com.qmusic.wear.ui.theme.LocalIsRoundScreen

/**
 * ScalingLazyColumn 内置表冠滚动的统一行为（snap 手感，与队列页一致）。
 *
 * 重要：必须通过 ScalingLazyColumn 的 rotaryScrollableBehavior 参数走【内置】表冠支持，
 * 不要再用 Modifier.rotaryScrollable 另挂一层——ScalingLazyColumn 内部已自带
 * requestFocusOnHierarchyActive().rotaryScrollable() 焦点协调机制，外挂第二层
 * focusTarget 会与之抢焦点（且官方文档明确禁止与 LaunchedEffect.requestFocus 混用），
 * 表现为「有震动但页面不滚」或「页面能滚但无震动/无指示条」等不确定行为。
 *
 * 触觉保持库默认（v2.0.1 同款）：不做任何附加震动。
 */
@Composable
fun qmRotarySnap(state: ScalingLazyListState): RotaryScrollableBehavior =
    RotaryScrollableDefaults.snapBehavior(state)

/**
 * 表冠自定义行为：把表冠旋转事件交给页面自带的 [RotaryScrollableBehavior] 处理
 * （页面级导航等非滚动用途）。用法：在页面根布局上 `.rotaryCustom(behavior)`。
 */
@Composable
fun Modifier.rotaryCustom(behavior: RotaryScrollableBehavior): Modifier {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    return rotaryScrollable(
        behavior = behavior,
        focusRequester = focusRequester,
    )
}

/**
 * ScalingLazyColumn 首项锚点模式：
 * - 圆表：库默认 [AutoCenteringParams]——首项自动居中，符合圆表弧面视口「内容绕屏心」的滚动惯例；
 * - 方表：null——关闭自动居中，列表从顶部 contentPadding 起自上而下排布，
 *   页面首部（标题/头卡片/搜索栏）贴回屏幕顶端，即传统瀑布流布局。
 */
@Composable
fun qmAutoCentering(): AutoCenteringParams? =
    if (LocalIsRoundScreen.current) AutoCenteringParams() else null

/**
 * ScalingLazyColumn 边缘缩放参数：方表禁用缩放（缩放会让非中心卡片变窄、视觉上不贴边，
 * 由物理黑边承担边界的贴边设计要求全宽显示）；圆表保留默认缩放（弧形感设计特性）。
 */
@Composable
fun edgeScalingParams(): ScalingParams =
    if (LocalIsRoundScreen.current) {
        ScalingLazyColumnDefaults.scalingParams()
    } else {
        // compose-foundation 1.6.2 的缩放参数名为 edgeScale（默认 0.7f）：
        // 方表置 1f 后边缘 item 不再被缩小，等价于禁用缩放
        ScalingLazyColumnDefaults.scalingParams(edgeScale = 1f)
    }

/** 圆表列表的横向防弧边内缩（方表为 0，边界交给物理黑边） */
private val RoundListHorizontalInset = 10.dp

/**
 * ScalingLazyColumn 未显式传 contentPadding 时的库默认值是 PaddingValues(horizontal = 10.dp)
 * （圆表防弧边惯例），方表会造成两侧 10dp 贴边空隙——方表归零、圆表保留默认。
 * 方表另加 [SquareTopContentPadding] 顶部留白：此类列表（如「我的」页）自行承载首部，
 * 关闭 [qmAutoCentering] 居中后需要一点顶距，避免首项紧贴屏幕物理边缘。
 * 注意：显式传了 contentPadding 的列表无需此 helper（默认值已被覆盖）。
 */
@Composable
fun edgeListPadding(): PaddingValues =
    if (LocalIsRoundScreen.current) PaddingValues(horizontal = RoundListHorizontalInset)
    else PaddingValues(top = SquareTopContentPadding)

/**
 * 与列表同宽的横向内缩：圆表返回 [RoundListHorizontalInset]，方表返回 0。
 * 用于不随列表滚动的固定头部（如搜索结果页的搜索框/分区标签），
 * 使它们与下方列表卡片左右对齐、同宽。
 */
@Composable
fun edgeListHorizontalInset(): Dp =
    if (LocalIsRoundScreen.current) RoundListHorizontalInset else 0.dp

/**
 * 在 [base] 的横向内缩基础上只叠加纵向额外留白，用于自行构造内容间距的页面。
 *
 * 这些页面原先写 `PaddingValues(top = …, bottom = …)`，等于把 [base] 的横向分量直接丢掉：
 * 方表本来就要求横向归零（边界交给物理黑边），所以看不出问题；
 * 圆表却因此失去防弧边内缩，卡片撑满圆屏直径、四角被表盘裁掉。
 * 圆表保留横向分量、方表维持归零，两个形态互不影响。
 */
@Composable
fun edgeContentPadding(
    base: PaddingValues,
    topExtra: Dp = 0.dp,
    bottomExtra: Dp = 0.dp,
): PaddingValues {
    val top = base.calculateTopPadding() + topExtra
    val bottom = base.calculateBottomPadding() + bottomExtra
    return if (LocalIsRoundScreen.current) {
        val layoutDirection = LocalLayoutDirection.current
        PaddingValues(
            start = base.calculateStartPadding(layoutDirection),
            end = base.calculateEndPadding(layoutDirection),
            top = top,
            bottom = bottom,
        )
    } else {
        PaddingValues(top = top, bottom = bottom)
    }
}