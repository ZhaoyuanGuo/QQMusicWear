package com.qmusic.wear

import com.qmusic.wear.data.local.LocalMusicIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [LocalMusicIndex] 纯逻辑测试：扩展名过滤、稳定曲目 id、目录递归收集与排序。
 * 不涉及 Android 依赖，可在 JVM 直接运行。
 */
class LocalMusicIndexTest {

    @Test
    fun `isSupported matches common formats ignoring case`() {
        assertTrue(LocalMusicIndex.isSupported("song.mp3"))
        assertTrue(LocalMusicIndex.isSupported("song.FLAC"))
        assertTrue(LocalMusicIndex.isSupported("track.m4A"))
        assertTrue(LocalMusicIndex.isSupported("a.b.ogg")) // 取最后一段扩展名
    }

    @Test
    fun `isSupported rejects non-audio and missing extension`() {
        assertFalse(LocalMusicIndex.isSupported("notes.txt"))
        assertFalse(LocalMusicIndex.isSupported("cover.jpg"))
        assertFalse(LocalMusicIndex.isSupported("noext"))
        // qmc/mgg 等加密格式不在原生解码支持内
        assertFalse(LocalMusicIndex.isSupported("song.qmc"))
    }

    @Test
    fun `stableId is deterministic and prefixed`() {
        val a = LocalMusicIndex.stableId("/sdcard/music/a.mp3")
        val b = LocalMusicIndex.stableId("/sdcard/music/a.mp3")
        val c = LocalMusicIndex.stableId("/sdcard/music/b.mp3")
        assertTrue(a.startsWith("local-"))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `collectAudioFiles recurses filters and sorts skipping empty files`() {
        val root = createTempDir("local-music-test")
        try {
            File(root, "sub").mkdirs()
            File(root, "z.mp3").writeText("data")
            File(root, "a.flac").writeText("data")
            File(root, "cover.jpg").writeText("data")
            File(root, "empty.mp3").writeText("")           // 0 字节应被忽略
            File(root, "sub/deep.m4a").writeText("data")

            val files = LocalMusicIndex.collectAudioFiles(root)
            val names = files.map { it.name }.toSet()

            assertEquals(3, files.size)
            assertEquals(setOf("a.flac", "deep.m4a", "z.mp3"), names)
            assertFalse("empty.mp3 should be excluded", names.contains("empty.mp3"))
            assertFalse("cover.jpg should be excluded", names.contains("cover.jpg"))
            // 按绝对路径小写升序稳定排序
            val paths = files.map { it.absolutePath.lowercase() }
            assertEquals(paths.sorted(), paths)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `collectAudioFiles returns empty for missing directory`() {
        val missing = File(createTempDir("missing-parent"), "does-not-exist")
        assertTrue(LocalMusicIndex.collectAudioFiles(missing).isEmpty())
    }

    private fun createTempDir(prefix: String): File =
        File(System.getProperty("java.io.tmpdir"), "$prefix-${System.nanoTime()}").apply { mkdirs() }
}
