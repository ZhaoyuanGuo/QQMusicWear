package com.qmusic.wear

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import com.qmusic.wear.data.player.NowPlaying
import kotlinx.coroutines.CoroutineScope

/** 页面导航宿主：AnimatedContent 承载方向化转场，路由内容见 AppRoutes.kt */
@Composable
internal fun AppNavHost(
    nav: ScreenNav,
    now: NowPlaying,
    lowPerf: Boolean,
    navScope: CoroutineScope,
) {
    AnimatedContent(
        targetState = nav.screen,
        transitionSpec = { screenTransition(lowPerf) },
        label = "page_nav",
    ) { s ->
        AppRoute(s, nav, now, navScope)
    }
}

/**
 * 方向化转场：
 * - 低配置模式直接硬切（省 GPU 合成与动画帧）；
 * - 歌词/评论/队列轻推淡入，其余按导航深度横向滑动。
 */
private fun AnimatedContentTransitionScope<Screen>.screenTransition(lowPerf: Boolean): ContentTransform {
    if (lowPerf) return EnterTransition.None togetherWith ExitTransition.None
    // 播放页↔歌词/评论/队列：这些页面与播放页背景明暗差异大（播放页封面背景偏亮、
    // 二级页纯黑），整屏硬推会在屏幕中间留下一道亮暗接缝（一半亮块 + 一半黑块）；
    // 改为交叉淡化 + 小幅位移，两屏在同一区域叠化，接缝消失、过渡柔和
    return when {
        initialState == Screen.Player &&
            (targetState == Screen.Lyrics || targetState == Screen.Comments) ->
            (fadeIn(tween(320)) + slideInHorizontally(tween(320)) { it / 4 }) togetherWith
                (fadeOut(tween(320)) + slideOutHorizontally(tween(320)) { -it / 6 })

        (initialState == Screen.Lyrics || initialState == Screen.Comments) &&
            targetState == Screen.Player ->
            (fadeIn(tween(320)) + slideInHorizontally(tween(320)) { -it / 4 }) togetherWith
                (fadeOut(tween(320)) + slideOutHorizontally(tween(320)) { it / 6 })

        initialState == Screen.Player && targetState == Screen.Queue ->
            (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 4 }) togetherWith
                (fadeOut(tween(320)) + slideOutVertically(tween(320)) { -it / 6 })

        initialState == Screen.Queue && targetState == Screen.Player ->
            (fadeIn(tween(320)) + slideInVertically(tween(320)) { -it / 4 }) togetherWith
                (fadeOut(tween(320)) + slideOutVertically(tween(320)) { it / 6 })

        navDepth(targetState) > navDepth(initialState) ->
            slideInHorizontally(tween(260)) { it } togetherWith
                slideOutHorizontally(tween(260)) { -it }

        navDepth(targetState) < navDepth(initialState) ->
            slideInHorizontally(tween(260)) { -it } togetherWith
                slideOutHorizontally(tween(260)) { it }

        else -> fadeIn(tween(220)) togetherWith fadeOut(tween(220))
    }
}