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
import com.qmusic.wear.data.api.int
import com.qmusic.wear.data.api.long
import com.qmusic.wear.data.api.str
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

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

    /** 队列持久化（切歌/暂停/退出时保存，启动时恢复） */
    private val queueStore = QueueStore(context)

    /** 启动恢复期间为 true：恢复注入队列触发的换曲不记历史/统计 */
    private var restoring = false

    /** 上次快照时间（轮询节流用） */
    private var lastPersistMs = 0L

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
                } ?: restoreQueue(c)
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
                if (restoring) {
                    // 启动恢复注入队列触发的换曲：不计历史/统计
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                        restoring = false
                    }
                } else {
                    val song = currentQueue.getOrNull(c.currentMediaItemIndex)
                    if (song != null) {
                        scope.launch(Dispatchers.IO) {
                            historyStore.record(song)
                            // 听歌统计（周时长/Top歌曲），失败不影响播放
                            runCatching { ServiceLocator.playStats.record(song) }
                        }
                    }
                    persistNow(c)
                }
                publish(c)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // 暂停瞬间快照一次进度（退出前最后一次播放位置）
                if (!isPlaying && !restoring && currentQueue.isNotEmpty()) persistNow(c)
                publish(c)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) failStreak = 0
                publish(c)
            }

            // 播放失败自愈：首次失败尝试本地文件/降音质重解析，仍失败自动跳下一首
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val song = currentQueue.getOrNull(c.currentMediaItemIndex) ?: return
                failStreak++
                scope.launch {
                    if (failStreak == 1) {
                        resolveFallback(song)?.let { fallback ->
                            replaceUrl(c.currentMediaItemIndex, fallback)
                            return@launch
                        }
                    }
                    // 降级无果：自动跳下一首；整队列均失败则停止，避免无限循环
                    if (failStreak < currentQueue.size) {
                        toast("无法播放《${song.name}》，已自动切换下一首")
                        mainExecutor.execute {
                            val cc = controller ?: return@execute
                            if (cc.hasNextMediaItem()) {
                                cc.seekToNextMediaItem()
                                cc.prepare()
                                cc.play()
                            } else {
                                cc.pause()
                            }
                        }
                    } else {
                        failStreak = 0
                        toast("连续多首无法播放，已停止（多为网络或版权限制）")
                        mainExecutor.execute { controller?.pause() }
                    }
                }
            }
        })
    }

    /** 连续播放失败计数（成功进入 READY 即清零） */
    private var failStreak = 0

    private fun toast(msg: String) {
        mainExecutor.execute {
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** 失败兜底：优先本地已下载文件，其次按音质从高到低逐级降档重解析 */
    private suspend fun resolveFallback(song: Song): ResolvedUrl? {
        ServiceLocator.downloads.findByMid(song.mid)?.let { d ->
            return ResolvedUrl(url = java.io.File(d.filePath).toURI().toString(), prefix = "LOCAL")
        }
        val current = runCatching { ServiceLocator.settingsStore.quality() }
            .getOrDefault(com.qmusic.wear.data.model.Quality.STANDARD)
        val lower = com.qmusic.wear.data.model.Quality.entries
            .filter { it.ordinal < current.ordinal }
            .sortedByDescending { it.ordinal }
        for (q in lower) {
            val r = runCatching {
                ServiceLocator.repository.resolveUrls(listOf(song), q).firstOrNull()
            }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    /** 用兜底地址替换队列中指定曲目并重新播放（保队列结构） */
    private fun replaceUrl(index: Int, url: ResolvedUrl) {
        if (index !in currentQueue.indices) return
        currentUrls = currentUrls.toMutableList().apply { set(index, url) }
        mainExecutor.execute {
            val c = controller ?: return@execute
            val items = currentQueue.mapIndexed { i, s -> s.toMediaItem(currentUrls.getOrNull(i)) }
            c.setMediaItems(items, index, 0)
            c.prepare()
            c.play()
        }
    }

    /**
     * 实时播放位置（框架侧插值，毫秒）。
     * state 轮询间隔 500ms，直接读会产生阶梯感；卡拉OK填充等需要平滑进度的
     * 场景应使用本方法按帧读取。
     */
    fun positionNow(): Long {
        val c = controller ?: return state.value.positionMs
        return runCatching { c.currentPosition }.getOrDefault(state.value.positionMs)
    }

    private fun startPolling(c: MediaController) {
        scope.launch {
            while (true) {
                publish(c)
                // 播放中每 5 秒快照一次进度，进程被杀也能恢复到最近位置
                // （序列化走 IO 线程，避免主线程周期性抖动）
                if (c.isPlaying && !restoring && currentQueue.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    if (now - lastPersistMs >= 5000) {
                        lastPersistMs = now
                        val queueSnapshot = currentQueue.toList()
                        val index = c.currentMediaItemIndex
                        val positionMs = c.currentPosition
                        scope.launch(Dispatchers.IO) {
                            queueStore.save(queueSnapshot, index, positionMs)
                        }
                    }
                }
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
        restoring = false
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
        val c = controller ?: return
        // 恢复的队列处于 IDLE 态：先 prepare 才能真正开播（未解析曲目随后走失败自愈）
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
        c.seekTo(index, 0)
        c.play()
    }

    /**
     * 点播加入队列：点击任意列表里的一首歌 = 追加到当前队列尾部并立即播放该曲。
     * 队列中其余歌曲保持不动；若该曲已在队列里则直接跳过去播放（避免重复入列）。
     * 已下载歌曲直接用本地文件，未下载按当前音质解析。
     */
    fun enqueueAndPlay(song: Song) {
        if (song.mid.isEmpty()) return
        restoring = false
        scope.launch {
            // 队列里已有该曲目：不追加，直接跳转播放（队列不发生变动）
            val existIdx = currentQueue.indexOfFirst { it.mid == song.mid }
            if (existIdx >= 0) {
                mainExecutor.execute {
                    controller?.seekTo(existIdx, 0)
                    controller?.prepare()
                    controller?.play()
                }
                return@launch
            }
            val resolved = ServiceLocator.downloads.findByMid(song.mid)?.let { d ->
                ResolvedUrl(
                    url = java.io.File(d.filePath).toURI().toString(),
                    prefix = "LOCAL",
                )
            } ?: run {
                val quality = ServiceLocator.settingsStore.quality()
                ServiceLocator.repository.resolveUrls(listOf(song), quality).firstOrNull()
            } ?: resolveFallback(song)
            if (resolved == null) {
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
            mainExecutor.execute {
                val c = controller ?: return@execute
                currentQueue = currentQueue + song
                currentUrls = currentUrls + resolved
                val target = currentQueue.lastIndex
                c.addMediaItem(song.toMediaItem(resolved))
                c.seekTo(target, 0)
                c.prepare()
                c.play()
            }
        }
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
            persistNow(c)
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
        if (c.isPlaying) {
            c.pause()
        } else {
            // 恢复的队列处于 IDLE 态：先 prepare 才能开播（未解析曲目随后走失败自愈）
            if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
            c.play()
        }
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

    // ---------------- 队列持久化与恢复 ----------------

    /**
     * 启动恢复：装回上次退出时的队列与进度。
     * 保持 IDLE 态不自动播放（不联网不响铃）；地址全部留空（about:blank 占位），
     * 用户点播放时触发失败自愈：本地文件优先 → 音质逐级降档重解析。
     */
    private fun restoreQueue(c: MediaController) {
        val snap = queueStore.read() ?: return
        if (snap.queue.isEmpty()) return
        restoring = true
        currentQueue = snap.queue
        currentUrls = List(snap.queue.size) { null }
        val index = snap.index.coerceIn(0, snap.queue.lastIndex)
        mainExecutor.execute {
            val cc = controller ?: return@execute
            val items = snap.queue.mapIndexed { i, s -> s.toMediaItem(currentUrls.getOrNull(i)) }
            cc.setMediaItems(items, index, snap.positionMs.coerceAtLeast(0))
            publish(cc)
        }
    }

    /** 保存队列快照（空队列不覆盖旧快照；恢复期间不保存） */
    private fun persistNow(c: MediaController) {
        if (restoring || currentQueue.isEmpty()) return
        lastPersistMs = System.currentTimeMillis()
        val pos = if (c.playbackState == Player.STATE_IDLE) 0L else c.currentPosition
        queueStore.save(currentQueue, c.currentMediaItemIndex, pos)
    }
}

/** 队列快照 */
private data class QueueSnapshot(
    val queue: List<Song>,
    val index: Int,
    val positionMs: Long,
)

/**
 * 播放队列持久化（SharedPreferences，手写 JSON 与 HistoryStore 同风格）。
 * 进程退出/被杀后下次启动恢复队列与进度。
 */
private class QueueStore(context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_player", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(): QueueSnapshot? = runCatching {
        val text = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        val obj = json.parseToJsonElement(text).jsonObject
        val queue = obj["queue"]?.jsonArray?.mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            if (s.str("mid").isEmpty()) return@mapNotNull null
            Song(
                songId = s.long("songId"),
                mid = s.str("mid"),
                name = s.str("name"),
                singers = s.str("singers"),
                albumName = s.str("albumName"),
                albumMid = s.str("albumMid"),
                mediaMid = s.str("mediaMid"),
                intervalSec = s.int("intervalSec"),
                songType = s.int("songType"),
                vip = (s["vip"] as? JsonPrimitive)?.content == "true",
                cover300 = s.str("cover300"),
                cover500 = s.str("cover500"),
            )
        }.orEmpty()
        if (queue.isEmpty()) return null
        QueueSnapshot(
            queue = queue,
            index = obj.long("index").toInt(),
            positionMs = obj.long("positionMs"),
        )
    }.getOrNull()

    fun save(queue: List<Song>, index: Int, positionMs: Long) {
        // 超长队列只保留尾部，防止 SharedPreferences 无限膨胀
        val dropped = (queue.size - MAX_QUEUE).coerceAtLeast(0)
        val kept = if (dropped > 0) queue.drop(dropped) else queue
        val idx = (index - dropped).coerceIn(0, kept.lastIndex)
        val obj = buildJsonObject {
            put("index", idx)
            put("positionMs", positionMs)
            put("ts", System.currentTimeMillis())
            put("queue", buildJsonArray {
                kept.forEach { s ->
                    add(buildJsonObject {
                        put("songId", s.songId)
                        put("mid", s.mid)
                        put("name", s.name)
                        put("singers", s.singers)
                        put("albumName", s.albumName)
                        put("albumMid", s.albumMid)
                        put("mediaMid", s.mediaMid)
                        put("intervalSec", s.intervalSec)
                        put("songType", s.songType)
                        put("vip", s.vip)
                        put("cover300", s.cover300)
                        put("cover500", s.cover500)
                    })
                }
            })
        }
        prefs.edit().putString(KEY_SNAPSHOT, obj.toString()).apply()
    }

    private companion object {
        const val KEY_SNAPSHOT = "queue_snapshot"
        const val MAX_QUEUE = 300
    }
}
