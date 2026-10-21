package com.qmusic.wear.data.player

/**
 * 播放状态轮询策略（纯逻辑，便于 JVM 单测）。
 *
 * 手表续航敏感：播放中需 500ms 刷新进度以保证 UI 平滑；
 * 暂停/空闲但用户看得见界面时降频到 2s（状态可能随时被操作改变）；
 * 后台且灭屏且未播放时进入休眠档 60s，仅作兜底唤醒——
 * 实际状态变化（亮屏/回前台/起播）都另有即时唤醒信号，不依赖轮询。
 */
internal object PollingPolicy {

    /** 播放中轮询间隔：进度条/时间需平滑刷新 */
    const val PLAYING_MS = 500L

    /** 暂停或空闲但前台亮屏时的轮询间隔：降频省电 */
    const val IDLE_MS = 2_000L

    /** 后台+灭屏+未播放：休眠档，只作唤醒信号丢失时的兜底（如 QPlay 远端指令） */
    const val DORMANT_MS = 60_000L

    /** 播放中队列快照的最小间隔（进程被杀后恢复进度用） */
    const val PERSIST_INTERVAL_MS = 5_000L

    /**
     * [interactive] = 前台且亮屏（用户看得见界面）。
     * 未播放且不交互时进入休眠档 [DORMANT_MS]。
     */
    fun intervalMs(isPlaying: Boolean, interactive: Boolean): Long = when {
        isPlaying -> PLAYING_MS
        interactive -> IDLE_MS
        else -> DORMANT_MS
    }

    /**
     * 是否应在此刻写入队列快照。
     * 仅在播放中、非恢复期、队列非空且距上次快照超过 [PERSIST_INTERVAL_MS] 时写盘。
     */
    fun shouldPersist(
        isPlaying: Boolean,
        restoring: Boolean,
        queueEmpty: Boolean,
        sinceLastPersistMs: Long,
    ): Boolean = isPlaying && !restoring && !queueEmpty && sinceLastPersistMs >= PERSIST_INTERVAL_MS
}
