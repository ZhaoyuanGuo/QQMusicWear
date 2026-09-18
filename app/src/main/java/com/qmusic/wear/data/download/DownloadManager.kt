package com.qmusic.wear.data.download

import android.content.Context
import com.qmusic.wear.data.api.int
import com.qmusic.wear.data.api.long
import com.qmusic.wear.data.api.str
import com.qmusic.wear.data.model.ResolvedUrl
import com.qmusic.wear.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import android.os.StatFs

/** 一条下载记录 */
data class Downloaded(
    val song: Song,
    val filePath: String,
    val prefix: String,
    val sizeBytes: Long,
    val downloadedAt: Long,
)

/** 批量下载进度（歌单「下载全部」） */
data class BatchState(
    val running: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val current: String = "",
)

/**
 * 歌曲下载管理：私有目录落盘 + SharedPreferences 记录索引。
 * 下载的歌曲在播放时优先使用本地文件（离线可播、零流量）。
 */
class DownloadManager(context: Context, private val http: okhttp3.OkHttpClient) {

    private val dir = File(context.filesDir, "downloads").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("qmusic_downloads", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _downloadsFlow = MutableStateFlow(read())
    val downloadsFlow: StateFlow<List<Downloaded>> = _downloadsFlow.asStateFlow()

    private val batchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _batch = MutableStateFlow(BatchState())
    val batch: StateFlow<BatchState> = _batch.asStateFlow()
    private var batchJob: Job? = null

    /** 下载目录可用空间（字节） */
    fun freeBytes(): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)

    fun isDownloaded(mid: String): Boolean = findByMid(mid) != null

    fun findByMid(mid: String): Downloaded? = _downloadsFlow.value.firstOrNull { it.song.mid == mid }

    /** 某首歌的本地播放地址（file:// URI），未下载返回 null */
    fun localUriOf(mid: String): String? = findByMid(mid)?.let { File(it.filePath).toURI().toString() }

    /** 下载一首歌到私有目录（流式写盘 + 进度回调），完成返回记录 */
    suspend fun download(
        song: Song,
        resolved: ResolvedUrl,
        onProgress: (Float) -> Unit = {},
    ): Downloaded = withContext(Dispatchers.IO) {
        val ext = resolved.ext.ifEmpty { "mp3" }
        val req = okhttp3.Request.Builder()
            .url(resolved.url)
            .apply {
                // 播放/下载请求头由音乐源插件 manifest 提供（防盗链）
                com.qmusic.wear.data.source.SourceManager.playbackHeaders.forEach { (k, v) ->
                    header(k, v)
                }
            }
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("下载失败 HTTP ${resp.code}")
            val body = resp.body ?: error("下载失败：空响应")
            val total = body.contentLength()
            val tmp = File(dir, "${song.mid}.tmp")
            try {
                var read = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (read <= 0) error("下载失败：内容为空")
                if (total > 0 && read < total) error("下载不完整 ($read/$total)")
                val finalFile = File(dir, "${song.mid}.$ext")
                if (finalFile.exists()) finalFile.delete()
                if (!tmp.renameTo(finalFile)) error("保存文件失败")
                val record = Downloaded(
                    song = song,
                    filePath = finalFile.absolutePath,
                    prefix = resolved.prefix,
                    sizeBytes = finalFile.length(),
                    downloadedAt = System.currentTimeMillis() / 1000,
                )
                upsert(record)
                // 预取歌词（原文/译文/罗马音）落盘：后台进行，不阻塞下载返回，失败不影响下载结果
                prefetchLyrics(song)
                record
            } finally {
                runCatching { if (tmp.exists()) tmp.delete() }
            }
        }
    }

    /** 删除一条下载（同时删文件） */
    fun remove(mid: String) {
        val target = findByMid(mid) ?: return
        runCatching { File(target.filePath).delete() }
        val list = _downloadsFlow.value.filterNot { it.song.mid == mid }
        _downloadsFlow.value = list
        persist(list)
    }

    /**
     * 批量下载（歌单「下载全部」）：串行逐首，已下载的自动跳过。
     * [resolve] 由调用方注入（解析下载地址），便于分层不依赖 ServiceLocator。
     */
    fun enqueueBatch(songs: List<Song>, resolve: suspend (Song) -> ResolvedUrl?) {
        if (_batch.value.running || songs.isEmpty()) return
        batchJob?.cancel()
        batchJob = batchScope.launch {
            val pending = songs.filter { !isDownloaded(it.mid) }
            _batch.value = BatchState(running = true, total = pending.size)
            var done = 0
            var failed = 0
            pending.forEach { song ->
                _batch.value = _batch.value.copy(current = song.name)
                try {
                    val resolved = resolve(song) ?: error("无法获取下载地址")
                    download(song, resolved)
                } catch (t: Throwable) {
                    failed++
                }
                done++
                _batch.value = _batch.value.copy(done = done, failed = failed)
            }
            _batch.value = _batch.value.copy(running = false, current = "")
        }
    }

    private fun upsert(record: Downloaded) {
        val list = _downloadsFlow.value.filterNot { it.song.mid == record.song.mid } + record
        _downloadsFlow.value = list
        persist(list)
    }

    /** 下载完成后预取歌词落盘（含译文/罗马音），离线歌词页可直读缓存 */
    private fun prefetchLyrics(song: Song) {
        if (song.mid.isEmpty()) return
        prefetchScope.launch {
            runCatching {
                val cache = com.qmusic.wear.ServiceLocator.lyricsCache
                if (cache.get(song.mid) != null) return@launch
                val repo = com.qmusic.wear.ServiceLocator.repository
                val text = runCatching { repo.lyricOf(song) }.getOrDefault("")
                if (text.isBlank()) return@launch
                val trans = runCatching { repo.lyricTransOf(song) }.getOrDefault("")
                val roma = runCatching { repo.lyricRomaOf(song) }.getOrDefault("")
                cache.put(song.mid, text, trans, roma)
            }
        }
    }

    // ------------------------- 持久化 -------------------------

    private fun read(): List<Downloaded> = runCatching {
        val text = prefs.getString(KEY_LIST, null) ?: return emptyList()
        json.parseToJsonElement(text).jsonArray.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val mid = obj.str("mid")
            if (mid.isEmpty()) return@mapNotNull null
            Downloaded(
                song = Song(
                    songId = obj.long("songId"),
                    mid = mid,
                    name = obj.str("name"),
                    singers = obj.str("singers"),
                    albumName = obj.str("albumName"),
                    albumMid = obj.str("albumMid"),
                    mediaMid = obj.str("mediaMid"),
                    intervalSec = obj.int("intervalSec"),
                    songType = obj.int("songType"),
                ),
                filePath = obj.str("filePath"),
                prefix = obj.str("prefix"),
                sizeBytes = obj.long("sizeBytes"),
                downloadedAt = obj.long("downloadedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun persist(list: List<Downloaded>) {
        val arr = buildJsonArray {
            list.forEach { d ->
                add(buildJsonObject {
                    put("songId", d.song.songId)
                    put("mid", d.song.mid)
                    put("name", d.song.name)
                    put("singers", d.song.singers)
                    put("albumName", d.song.albumName)
                    put("albumMid", d.song.albumMid)
                    put("mediaMid", d.song.mediaMid)
                    put("intervalSec", d.song.intervalSec)
                    put("songType", d.song.songType)
                    put("filePath", d.filePath)
                    put("prefix", d.prefix)
                    put("sizeBytes", d.sizeBytes)
                    put("downloadedAt", d.downloadedAt)
                })
            }
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private companion object {
        const val KEY_LIST = "downloaded_songs"
    }
}
