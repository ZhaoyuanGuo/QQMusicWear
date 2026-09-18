package com.qmusic.wear.data.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.qmusic.wear.MainActivity
import com.qmusic.wear.R

/**
 * 前台媒体播放服务：持有 ExoPlayer 与 MediaSession，
 * UI 层通过 PlayerConnection 里的 MediaController 与之交互。
 *
 * 额外职责（不触碰 Media3 自带的前台通知，二者共存）：
 * - Wear OS 4 Ongoing Activity 常驻卡片：独立通知通道 + id=42，显示当前歌名；
 * - 表盘 Complication 数据源：把歌名/歌手/播放状态写入 SharedPreferences，
 *   供 PlayingComplicationService 秒读（Complication 要求快速返回，禁止网络）。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 是否真正在播放（暂停为 false），同步给 Complication */
    private var playing = false

    private val notificationManager
        get() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private val playerListener = object : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncComplication()
            updateOngoingActivity()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            syncComplication()
            updateOngoingActivity()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            playing = isPlaying
            syncComplication()
            updateOngoingActivity()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)
        mediaSession = MediaSession.Builder(this, player).build()
        notificationManager.createNotificationChannel(
            NotificationChannel(
                ONGOING_CHANNEL_ID,
                getString(R.string.ongoing_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // 没在播放且队列已空时结束服务，避免后台常驻
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 && player.playbackState == Player.STATE_IDLE) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        // 服务结束即无活动会话：撤下 Ongoing Activity 并清空 Complication 数据
        notificationManager.cancel(ONGOING_NOTIFICATION_ID)
        getSharedPreferences(COMPLICATION_PREFS, MODE_PRIVATE).edit().clear().apply()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Ongoing Activity（Wear OS 4+ 常驻卡片）
    // ------------------------------------------------------------------

    /** 播放/切歌/元数据变化时启动或更新；无当前歌曲时取消 */
    private fun updateOngoingActivity() {
        val item = mediaSession?.player?.currentMediaItem
        if (item == null) {
            notificationManager.cancel(ONGOING_NOTIFICATION_ID)
            return
        }
        val title = item.mediaMetadata.title?.toString().orEmpty().ifEmpty { "未知歌曲" }
        val artist = item.mediaMetadata.artist?.toString().orEmpty()
        // 暂停时在卡片状态里标注出来，方便表盘/桌面快速识别
        val statusText = if (playing) title else "已暂停：$title"

        val notification = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(launchIntent())
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)

        val ongoingActivity = OngoingActivity.Builder(this, ONGOING_NOTIFICATION_ID, notification)
            // wear-ongoing 1.0.0 的 category 是字符串参数，"playback" 即 1.1.0 起的
            // OngoingActivityCategory.PLAYBACK 常量值
            .setCategory(CATEGORY_PLAYBACK)
            .setStaticIcon(R.drawable.ic_play)
            // wear-ongoing 1.0.0 的启动入口即 setTouchIntent（点击卡片打开应用）
            .setTouchIntent(launchIntent())
            .setStatus(
                Status.Builder()
                    .addPart(STATUS_PART_TITLE, Status.TextPart(statusText))
                    .build(),
            )
            .build()
        ongoingActivity.apply(this)
        notificationManager.notify(ONGOING_NOTIFICATION_ID, notification.build())
    }

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    // ------------------------------------------------------------------
    // 表盘 Complication 数据（SharedPreferences 秒读）
    // ------------------------------------------------------------------

    /** 把歌名/歌手/播放状态写入 SharedPreferences，PlayingComplicationService 直接读取 */
    private fun syncComplication() {
        val metadata = mediaSession?.player?.currentMediaItem?.mediaMetadata
        getSharedPreferences(COMPLICATION_PREFS, MODE_PRIVATE).edit()
            .putString(KEY_NAME, metadata?.title?.toString().orEmpty())
            .putString(KEY_SINGERS, metadata?.artist?.toString().orEmpty())
            .putBoolean(KEY_PLAYING, playing)
            .apply()
    }

    private companion object {
        const val ONGOING_CHANNEL_ID = "playback_ongoing"
        const val ONGOING_NOTIFICATION_ID = 42
        const val STATUS_PART_TITLE = "title"
        const val CATEGORY_PLAYBACK = "playback"
        const val COMPLICATION_PREFS = "qmusic_complication"
        const val KEY_NAME = "name"
        const val KEY_SINGERS = "singers"
        const val KEY_PLAYING = "playing"
    }
}
