package com.qmusic.wear.data.local

import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.qmusic.wear.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 本地音乐扫描的纯逻辑部分（无 Android 依赖，可 JVM 单测）：
 * 扩展名过滤、稳定曲目标识生成、自有目录递归收集。
 */
object LocalMusicIndex {

    /** Media3/ExoPlayer 原生可解码的常见流媒体格式（不引入额外解码器） */
    val SUPPORTED_EXT = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav")

    /** 是否为受支持的音频文件（按扩展名，忽略大小写） */
    fun isSupported(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXT

    /**
     * 由文件绝对路径生成稳定且低碰撞的曲目 mid：
     * 前缀 local- + 路径哈希（正数），保证队列去重/正在播放追踪可用。
     */
    fun stableId(path: String): String = "local-" + (path.hashCode().toLong() and 0xffffffffL)

    /** MediaStore 曲目的稳定 mid：ms- + 数据库行 id */
    fun mediaStoreId(id: Long): String = "ms-$id"

    /**
     * 递归收集目录下受支持的音频文件，按相对路径名排序（稳定顺序）。
     * 目录不存在或不可读时返回空列表，不抛异常。
     */
    fun collectAudioFiles(root: File): List<File> {
        if (!root.isDirectory || !root.canRead()) return emptyList()
        val out = ArrayList<File>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (f in children) {
                if (f.isDirectory) stack.addLast(f)
                else if (f.isFile && f.length() > 0 && isSupported(f.name)) out.add(f)
            }
        }
        return out.sortedBy { it.absolutePath.lowercase() }
    }
}

/**
 * 本地音乐扫描器：列出「本设备上可播放的音频」，来源合并两类——
 * 1) 系统媒体库 MediaStore：全设备被索引的音频，涵盖其它音乐 App / 用户放进共享目录
 *    （/sdcard/Music、/sdcard/Download 等）的文件；需运行时音频读取权限。
 * 2) 应用专属外部目录 <getExternalFilesDir>/music：无需权限，可经 adb/文件管理器推入。
 *
 * 说明：其它 App 私有目录（Android/data/<pkg>、/data/data/<pkg>）下的文件，尤其加密格式
 * （.qmc/.ncm/.mgg 等）受 Android 沙箱与版权限制无法读取，不在本功能覆盖范围。
 *
 * 播放统一走 PlayerConnection.playLocal（content:// 或 file:// 直连本地解码，不联网）。
 */
class LocalMusicScanner(private val context: Context) {

    private val prefs = context.getSharedPreferences("qmusic_local_music", Context.MODE_PRIVATE)

    private val musicDir: File by lazy {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "music").apply { mkdirs() }
    }

    /** 应用专属外部目录绝对路径（无权限来源，UI 空态提示推文件到此路径） */
    fun directoryPath(): String = musicDir.absolutePath

    private val _tracks = MutableStateFlow<List<LocalTrack>>(emptyList())
    val tracksFlow: StateFlow<List<LocalTrack>> = _tracks.asStateFlow()

    private var loadedFromCache = false

    /** 音频读取权限（API 33+ 用 READ_MEDIA_AUDIO，更低版本用 READ_EXTERNAL_STORAGE） */
    val audioPermission: String
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /** 是否已授予音频读取权限；未授予时仅能扫描自有目录 */
    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED

    /** 已扫描出的曲目（首次访问时尝试从缓存装载，不触发扫描） */
    fun currentTracks(): List<LocalTrack> {
        if (!loadedFromCache) {
            loadedFromCache = true
            if (_tracks.value.isEmpty()) _tracks.value = readCache()
        }
        return _tracks.value
    }

    /**
     * 重新扫描并刷新缓存（MediaStore + 自有目录合并去重）。耗时走 IO 线程。
     * [onProgress] 回调（当前序号, 总数），供 UI 展示进度。
     */
    suspend fun rescan(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<LocalTrack> =
        withContext(Dispatchers.IO) {
            val merged = LinkedHashMap<String, LocalTrack>() // key=uri，去重且保序

            // 1) MediaStore：全设备被索引音频（需权限）
            if (hasAudioPermission()) {
                queryMediaStore().forEach { merged[it.uri] = it }
            }

            // 2) 自有目录（无需权限）
            val files = LocalMusicIndex.collectAudioFiles(musicDir)
            val total = merged.size + files.size
            var done = merged.size
            files.forEach { f ->
                val t = buildFileTrack(f)
                merged.putIfAbsent(t.uri, t)
                done++
                onProgress(done, total)
            }

            val tracks = merged.values.toList()
            _tracks.value = tracks
            loadedFromCache = true
            writeCache(tracks)
            tracks
        }

    /**
     * 删除一首本地曲目：MediaStore 来源删数据库行（连带删文件），
     * 自有目录来源删文件。目录外的文件一律不删。
     */
    fun remove(track: LocalTrack) {
        if (track.mediaStoreId > 0) {
            runCatching {
                context.contentResolver.delete(
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.mediaStoreId),
                    null, null,
                )
            }
        } else {
            runCatching {
                val path = Uri.parse(track.uri).path
                if (path != null) {
                    val f = File(path)
                    if (f.absolutePath.startsWith(musicDir.absolutePath)) f.delete()
                }
            }
        }
        val list = _tracks.value.filterNot { it.uri == track.uri }
        _tracks.value = list
        writeCache(list)
    }

    // ------------------------- MediaStore 查询 -------------------------

    private fun queryMediaStore(): List<LocalTrack> {
        val out = ArrayList<LocalTrack>()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        // 仅音乐（排除铃声/通知/闹钟），按标题排序
        runCatching {
            context.contentResolver.query(
                collection, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE LOCALIZED ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol).orEmpty()
                    val artist = c.getString(artistCol).orEmpty()
                    val album = c.getString(albumCol).orEmpty()
                    val albumId = c.getLong(albumIdCol)
                    val durMs = c.getLong(durCol)
                    val size = c.getLong(sizeCol)
                    val uri = ContentUris.withAppendedId(collection, id).toString()
                    val cover = if (albumId > 0) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"), albumId,
                        ).toString()
                    } else ""
                    val song = Song(
                        mid = LocalMusicIndex.mediaStoreId(id),
                        name = title.ifBlank { "未知歌曲" },
                        singers = artist.ifBlank { "<unknown>" }.let { if (it == "<unknown>") "本地音乐" else it },
                        albumName = album,
                        intervalSec = (durMs / 1000).toInt(),
                        cover300 = cover,
                        cover500 = cover,
                    )
                    out.add(LocalTrack(song = song, uri = uri, sizeBytes = size, mediaStoreId = id))
                }
            }
        }
        return out
    }

    // ------------------------- 自有目录（文件）来源 -------------------------

    private fun buildFileTrack(file: File): LocalTrack {
        val id = LocalMusicIndex.stableId(file.absolutePath)
        val meta = readMetadata(file)
        val fallbackTitle = file.nameWithoutExtension.substringBeforeLast('.')
        val song = Song(
            mid = id,
            name = meta.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            singers = meta.artist?.takeIf { it.isNotBlank() } ?: "本地音乐",
            albumName = meta.album.orEmpty(),
            intervalSec = meta.durationSec,
            cover300 = meta.coverUri ?: "",
            cover500 = meta.coverUri ?: "",
        )
        return LocalTrack(
            song = song,
            uri = "file://" + file.absolutePath,
            sizeBytes = file.length(),
            mediaStoreId = 0,
        )
    }

    private data class Meta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val durationSec: Int,
        val coverUri: String?,
    )

    private fun readMetadata(file: File): Meta {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            Meta(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                durationSec = (durMs / 1000).toInt(),
                coverUri = extractCover(file, r),
            )
        } catch (t: Throwable) {
            Meta(null, null, null, 0, null)
        } finally {
            runCatching { r.release() }
        }
    }

    /** 抽出内嵌封面写入缓存文件，返回 file:// URI（Coil 可直接加载）；无封面返回 null */
    private fun extractCover(file: File, r: MediaMetadataRetriever): String? {
        val art = runCatching { r.embeddedPicture }.getOrNull() ?: return null
        if (art.isEmpty()) return null
        return runCatching {
            val dir = File(context.cacheDir, "local_covers").apply { mkdirs() }
            val out = File(dir, LocalMusicIndex.stableId(file.absolutePath) + ".art")
            if (!out.exists() || out.length() != art.size.toLong()) out.writeBytes(art)
            "file://" + out.absolutePath
        }.getOrNull()
    }

    // ------------------------- 缓存持久化 -------------------------

    // 行内 9 字段（\t 分隔，字符串字段转义）：
    // uri / name / singers / album / mid / durationSec / cover / sizeBytes / mediaStoreId
    private fun readCache(): List<LocalTrack> = runCatching {
        val text = prefs.getString(KEY_LIST, null) ?: return emptyList()
        text.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split('\t')
            if (p.size < 9) return@mapNotNull null
            val uri = p[0]
            // file:// 来源需文件仍在；content:// 来源信任媒体库
            if (uri.startsWith("file://")) {
                val path = uri.removePrefix("file://")
                if (!File(path).isFile) return@mapNotNull null
            }
            val cover = decode(p[6])
            LocalTrack(
                song = Song(
                    mid = p[4],
                    name = decode(p[1]),
                    singers = decode(p[2]),
                    albumName = decode(p[3]),
                    intervalSec = p[5].toIntOrNull() ?: 0,
                    cover300 = cover,
                    cover500 = cover,
                ),
                uri = uri,
                sizeBytes = p[7].toLongOrNull() ?: 0L,
                mediaStoreId = p[8].toLongOrNull() ?: 0L,
            )
        }
    }.getOrDefault(emptyList())

    private fun writeCache(tracks: List<LocalTrack>) {
        val text = tracks.joinToString("\n") { t ->
            listOf(
                t.uri,
                encode(t.song.name),
                encode(t.song.singers),
                encode(t.song.albumName),
                t.song.mid,
                t.song.intervalSec.toString(),
                encode(t.song.cover300),
                t.sizeBytes.toString(),
                t.mediaStoreId.toString(),
            ).joinToString("\t")
        }
        prefs.edit().putString(KEY_LIST, text).apply()
    }

    private fun encode(s: String): String =
        s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")

    private fun decode(s: String): String =
        s.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")

    private companion object {
        const val KEY_LIST = "local_tracks"
    }
}

/**
 * 一条本地曲目：Song（供列表展示/队列复用）+ 可播放的本地地址 + 体积 + 媒体库行 id。
 * [uri] 为 content:// 或 file://；[mediaStoreId] > 0 表示来自系统媒体库（可经 ContentResolver 删除）。
 */
data class LocalTrack(
    val song: Song,
    val uri: String,
    val sizeBytes: Long,
    val mediaStoreId: Long = 0L,
)
