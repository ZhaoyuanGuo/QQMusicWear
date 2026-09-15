package com.qmusic.wear.data.player

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.qmusic.wear.CrashLog
import com.qmusic.wear.data.model.PlayMode
import com.qmusic.wear.ServiceLocator
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.Song
import com.qmusic.wear.data.store.HistoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 播放器对外状态 */
data class NowPlaying(
    val song: Song? = null,
    val queue: List<Song> = emptyList(),
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val qualityPrefix: String = "",
)

/**
 * MediaController 封装：UI 层统一从这里读写播放状态。
 */
class PlayerConnection(
    private val context: Context,
    private val historyStore: HistoryStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    private val _playMode = MutableStateFlow(PlayMode.SEQUENTIAL)
    val playMode: StateFlow<PlayMode> = _playMode.asStateFlow()

    /** 当前队列的解析结果缓存，便于切音质时保持位置 */
    private var currentQueue: List<Song> = emptyList()
    private var currentUrls: List<ResolvedUrl?> = emptyList()

    private var controller: MediaController? = null
    private var pendingPlay: PendingPlay? = null

    private data class PendingPlay(val items: List<MediaItem>, val startIndex: Int)

    private val mainExecutor: java.util.concurrent.Executor
        get() = ContextCompat.getMainExecutor(context)

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                applyMode(_playMode.value)
                attachListener(c)
                startPolling(c)
                pendingPlay?.let { p ->
                    c.setMediaItems(p.items, p.startIndex, 0)
                    c.prepare()
                    c.play()
                    pendingPlay = null
                }
            } catch (t: Throwable) {
                android.util.Log.e("PlayerConnection", "MediaController connect failed", t)
            }
        }, mainExecutor)

        // 读取持久化的播放模式并持续同步
        scope.launch {
            ServiceLocator.settingsStore.playModeFlow.collect { mode ->
                _playMode.value = mode
                applyMode(mode)
            }
        }
    }

    /** 切换播放模式：顺序 / 单曲循环 / 随机 */
    fun setPlayMode(mode: PlayMode) {
        _playMode.value = mode
        applyMode(mode)
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            ServiceLocator.settingsStore.setPlayMode(mode)
        }
    }

    private fun applyMode(mode: PlayMode) {
        val c = controller ?: return
        when (mode) {
            PlayMode.SEQUENTIAL -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_OFF
            }
            PlayMode.REPEAT_ONE -> {
                c.shuffleModeEnabled = false
                c.repeatMode = Player.REPEAT_MODE_ONE
            }
            PlayMode.RANDOM -> {
                c.shuffleModeEnabled = true
                c.repeatMode = Player.REPEAT_MODE_ALL
            }
        }
    }

    private fun attachListener(c: MediaController) {
        c.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                val song = currentQueue.getOrNull(c.currentMediaItemIndex)
                if (song != null) {
                    scope.launch(Dispatchers.IO) { historyStore.record(song) }
                }
                publish(c)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish(c)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                publish(c)
            }
        })
    }

    private fun startPolling(c: MediaController) {
        scope.launch {
            while (true) {
                publish(c)
                delay(500)
            }
        }
    }

    private fun publish(c: MediaController) {
        val index = c.currentMediaItemIndex
        _state.value = NowPlaying(
            song = currentQueue.getOrNull(index),
            queue = currentQueue,
            queueIndex = index,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0L,
            qualityPrefix = currentUrls.getOrNull(index)?.prefix.orEmpty(),
        )
    }

    /** 用解析好的地址填充队列并开始播放（MediaController 未就绪时暂存，连接后自动播放） */
    fun play(songs: List<Song>, urls: List<ResolvedUrl?>, startIndex: Int = 0) {
        currentQueue = songs
        currentUrls = urls
        val items = songs.mapIndexed { i, song -> song.toMediaItem(urls.getOrNull(i)) }
        mainExecutor.execute {
            val c = controller
            if (c == null) {
                pendingPlay = PendingPlay(items, startIndex)
            } else {
                c.setMediaItems(items, startIndex, 0)
                c.prepare()
                c.play()
            }
        }
    }

    fun playAt(index: Int) {
        controller?.seekTo(index, 0)
        controller?.play()
    }

    /** 从队列移除指定曲目（正在播放的不可移除） */
    fun removeAt(index: Int) {
        if (index == controller?.currentMediaItemIndex) return
        mainExecutor.execute {
            val c = controller ?: return@execute
            if (index !in currentQueue.indices) return@execute
            currentQueue = currentQueue.toMutableList().apply { removeAt(index) }
            currentUrls = currentUrls.toMutableList().apply { removeAt(index) }
            c.removeMediaItem(index)
        }
    }

    /** 一键播放列表：已下载歌曲直接用本地文件（离线可播），其余走网络解析 */
    fun playFromList(songs: List<Song>, mid: String) {
        scope.launch {
            val localUrls: List<ResolvedUrl?> = songs.map { s ->
                ServiceLocator.downloads.findByMid(s.mid)?.let { d ->
                    ResolvedUrl(
                        url = java.io.File(d.filePath).toURI().toString(),
                        prefix = "LOCAL",
                    )
                }
            }
            // 全部已下载时跳过网络解析（真正离线可用）
            val resolved: List<ResolvedUrl?> = if (localUrls.any { it == null }) {
                val quality = ServiceLocator.settingsStore.quality()
                ServiceLocator.repository.resolveUrls(songs, quality)
            } else {
                emptyList()
            }
            val urls = songs.mapIndexed { i, _ -> localUrls[i] ?: resolved.getOrNull(i) }
            val playable = songs.mapIndexedNotNull { i, s ->
                urls[i]?.let { s to it }
            }
            if (playable.isEmpty()) {
                // 明确反馈，避免点击后无任何响应；附带诊断信息便于排障
                val dbg = ServiceLocator.repository.lastResolveDebug
                mainExecutor.execute {
                    android.widget.Toast.makeText(
                        context,
                        "无法获取播放地址（网络或版权限制）" + if (dbg.isNotEmpty()) "\n$dbg" else "",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            val startIndex = playable.indexOfFirst { it.first.mid == mid }
                .let { if (it >= 0) it else 0 }
            play(playable.map { it.first }, playable.map { it.second }, startIndex)
        }
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    /** 直接暂停（睡眠定时器到时调用） */
    fun pause() {
        controller?.pause()
    }

    fun next() {
        if (controller?.hasNextMediaItem() == true) controller?.seekToNextMediaItem()
    }

    fun previous() {
        if (controller?.hasPreviousMediaItem() == true) controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    /** 切换音质：重新解析当前队列，保持曲目与进度 */
    fun switchQuality(newUrls: List<ResolvedUrl?>) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        val position = c.currentPosition
        currentUrls = newUrls
        val items = currentQueue.mapIndexed { i, song -> song.toMediaItem(newUrls.getOrNull(i)) }
        c.setMediaItems(items, index, position)
        c.prepare()
        if (c.playWhenReady) c.play()
    }

    // ---------------- 音量（系统媒体音量） ----------------

    val maxVolume: Int
        get() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    val currentVolume: Int
        get() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

    fun setVolume(value: Int) {
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            value.coerceIn(0, maxVolume),
            0,
        )
    }

    private fun Song.toMediaItem(resolved: ResolvedUrl?): MediaItem {
        val uri = resolved?.url.orEmpty()
        return MediaItem.Builder()
            .setMediaId(mid)
            .setUri(uri.ifEmpty { "about:blank" })
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(name)
                    .setArtist(singers)
                    .setAlbumTitle(albumName)
                    .setArtworkUri(cover300.takeIf { it.isNotEmpty() }?.let { android.net.Uri.parse(it) })
                    .build(),
            )
            .build()
    }
}
