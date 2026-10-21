package com.qmusic.wear

import com.qmusic.wear.data.update.UpdateInfo
import com.qmusic.wear.data.update.isForced
import com.qmusic.wear.data.update.isNewerThan
import com.qmusic.wear.data.update.parseUpdateInfo
import com.qmusic.wear.data.update.resolveApkUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 应用内更新契约单测：version.json 解析 / 版本比较 / 强制更新判定 / APK 相对地址解析。
 * 纯 JVM，不触 Android API（与 SourceDtosTest 同层约定）。
 */
class AppUpdateTest {

    private val fullJson = """
        {
          "versionCode": 42,
          "versionName": "3.1.0",
          "apkUrl": "http://111.229.33.249/qqmusicwear/update/QQMusicWear-v3.1.0.apk",
          "notes": "修复若干问题",
          "minVersionCode": 40,
          "sizeBytes": 4764159,
          "md5": "8A231FFAB3E1FF1D94CB747715FB9F83"
        }
    """.trimIndent()

    @Test
    fun `parse full version json`() {
        val info = parseUpdateInfo(fullJson)!!
        assertEquals(42, info.versionCode)
        assertEquals("3.1.0", info.versionName)
        assertEquals("修复若干问题", info.notes)
        assertEquals(40, info.minVersionCode)
        assertEquals(4764159L, info.sizeBytes)
        // md5 统一小写，比较时免于大小写坑
        assertEquals("8a231ffab3e1ff1d94cb747715fb9f83", info.md5)
    }

    @Test
    fun `parse applies defaults for optional fields`() {
        val info = parseUpdateInfo(
            """{"versionCode": 2, "versionName": "1.1", "apkUrl": "a.apk"}""",
        )!!
        assertEquals(0, info.minVersionCode)
        assertEquals(0L, info.sizeBytes)
        assertEquals("", info.md5)
        assertEquals("", info.notes)
    }

    @Test
    fun `parse rejects missing or invalid required fields`() {
        assertNull(parseUpdateInfo("""{"versionName": "1.0", "apkUrl": "a.apk"}"""))
        assertNull(parseUpdateInfo("""{"versionCode": 0, "apkUrl": "a.apk"}"""))
        assertNull(parseUpdateInfo("""{"versionCode": 3}"""))
        assertNull(parseUpdateInfo("""{"versionCode": 3, "apkUrl": "  "}"""))
        assertNull(parseUpdateInfo("not a json"))
        assertNull(parseUpdateInfo("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun `newer and forced comparison`() {
        val info = UpdateInfo(
            versionCode = 42, versionName = "3.1.0", apkUrl = "x.apk",
            notes = "", minVersionCode = 40, sizeBytes = 0, md5 = "",
        )
        assertTrue(info.isNewerThan(41))
        assertFalse(info.isNewerThan(42))
        assertFalse(info.isNewerThan(43))
        // 低于 minVersionCode 强制；等于则不强制（已满足要求）
        assertTrue(info.isForced(39))
        assertFalse(info.isForced(40))
        assertFalse(info.isForced(41))
    }

    @Test
    fun `no forced when minVersionCode absent`() {
        val info = UpdateInfo(
            versionCode = 42, versionName = "", apkUrl = "x.apk",
            notes = "", minVersionCode = 0, sizeBytes = 0, md5 = "",
        )
        assertFalse(info.isForced(1))
    }

    @Test
    fun `resolve apk url`() {
        val manifest = "http://111.229.33.249/qqmusicwear/update/version.json"
        // 绝对地址原样
        assertEquals(
            "https://cos.example.com/a.apk",
            resolveApkUrl(manifest, "https://cos.example.com/a.apk"),
        )
        // 裸文件名 → 挂到 version.json 同目录（镜像共用一份 JSON 的关键）
        assertEquals(
            "http://111.229.33.249/qqmusicwear/update/QQMusicWear-v1.apk",
            resolveApkUrl(manifest, "QQMusicWear-v1.apk"),
        )
        // 带前导斜杠 → 拼到目录
        assertEquals(
            "http://111.229.33.249/qqmusicwear/update/pkg/a.apk",
            resolveApkUrl(manifest, "/pkg/a.apk"),
        )
        // 根路径的 version.json（如 CloudBase 镜像）目录退化为 host 根
        assertEquals(
            "https://mirror.example.com/a.apk",
            resolveApkUrl("https://mirror.example.com/version.json", "a.apk"),
        )
    }
}
