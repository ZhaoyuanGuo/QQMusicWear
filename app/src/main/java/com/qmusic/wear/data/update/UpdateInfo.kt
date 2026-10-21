package com.qmusic.wear.data.update

import com.qmusic.wear.data.api.asIntOrNull
import com.qmusic.wear.data.api.asLongOrNull
import com.qmusic.wear.data.api.asStringOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * 服务器 version.json 解析出的应用更新信息。
 * 纯数据 + 纯函数，不触 Android API，可被 JVM 契约单测覆盖。
 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    /** APK 直链；也支持相对 version.json 所在目录的路径/文件名（多镜像共用一份 JSON） */
    val apkUrl: String,
    /** 更新说明（弹窗展示） */
    val notes: String,
    /** 低于该 versionCode 强制更新（0 = 不强制） */
    val minVersionCode: Int,
    /** 安装包大小（0 = 未知，UI 不展示体积） */
    val sizeBytes: Long,
    /** APK 的 MD5（空 = 服务端未提供，跳过校验） */
    val md5: String,
)

/** 本地版本是否落后于远端（弹「新版本」的条件） */
fun UpdateInfo.isNewerThan(localVersionCode: Int): Boolean = versionCode > localVersionCode

/** 本地版本低于 minVersionCode：必须更新，弹窗不可跳过 */
fun UpdateInfo.isForced(localVersionCode: Int): Boolean =
    minVersionCode > 0 && localVersionCode < minVersionCode

/**
 * 解析 APK 下载地址：绝对 URL 原样返回；相对值按 version.json 所在目录拼接，
 * 这样同一份 JSON 挂到任意镜像/服务器下都能自洽。
 */
fun resolveApkUrl(manifestUrl: String, apkUrl: String): String {
    if (apkUrl.startsWith("http://") || apkUrl.startsWith("https://")) return apkUrl
    val dir = manifestUrl.substringBeforeLast('/', missingDelimiterValue = "")
    return if (apkUrl.startsWith("/")) "$dir$apkUrl" else "$dir/$apkUrl"
}

/** 解析 version.json；缺必填字段/非法 JSON 返回 null（解析失败一律视为「无更新信息」，不误报） */
fun parseUpdateInfo(jsonText: String): UpdateInfo? = runCatching {
    val obj = Json.parseToJsonElement(jsonText).jsonObject
    val versionCode = obj["versionCode"].asIntOrNull(0)
    if (versionCode <= 0) return null
    val apkUrl = obj["apkUrl"].asStringOrNull().orEmpty().trim()
    if (apkUrl.isEmpty()) return null
    UpdateInfo(
        versionCode = versionCode,
        versionName = obj["versionName"].asStringOrNull().orEmpty().trim(),
        apkUrl = apkUrl,
        notes = obj["notes"].asStringOrNull().orEmpty().trim(),
        minVersionCode = obj["minVersionCode"].asIntOrNull(0),
        sizeBytes = obj["sizeBytes"].asLongOrNull(0L),
        md5 = obj["md5"].asStringOrNull().orEmpty().trim().lowercase(),
    )
}.getOrNull()
