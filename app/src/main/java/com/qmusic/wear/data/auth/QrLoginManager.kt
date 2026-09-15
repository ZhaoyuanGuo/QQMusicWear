package com.qmusic.wear.data.auth

import com.qmusic.wear.data.api.Credential
import com.qmusic.wear.data.source.SourceDtos
import com.qmusic.wear.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** 扫码登录过程事件（UI 层据此驱动状态机） */
sealed class QrLoginEvent {
    /** 二维码图片已就绪 */
    data class QrReady(val bytes: ByteArray) : QrLoginEvent()

    /** 等待扫码 */
    data object WaitingScan : QrLoginEvent()

    /** 已扫描，等待手机确认 */
    data object ScannedConfirm : QrLoginEvent()

    /** 二维码过期 */
    data object Expired : QrLoginEvent()

    /** 用户拒绝/取消 */
    data object Refused : QrLoginEvent()

    /** 网络或其他错误 */
    data class Error(val message: String) : QrLoginEvent()

    /** 登录成功 */
    data class Success(val credential: Credential) : QrLoginEvent()
}

/**
 * QQ 音乐 Web 扫码登录门面。
 *
 * 完整协议链路（会话预热 -> 二维码获取 -> 轮询 -> 授权跳转链收集 Cookie）
 * 全部在可下载的音乐源插件里，通过 qmu.emit 桥接事件流回宿主。
 */
class QrLoginManager {

    /** 源插件返回的登录凭据 */
    @Serializable
    private data class LoginCredDto(
        val musicid: Long = 0L,
        val musickey: String = "",
        val strMusicid: String = "",
        val encryptUin: String = "",
        val nick: String = "",
        val avatarUrl: String = "",
    )

    /**
     * 完整登录流程。成功返回 Credential 并发出 [QrLoginEvent.Success]，
     * 过期/取消/错误只发事件并返回 null（调用方可 retry 重新发起）。
     */
    suspend fun login(onEvent: (QrLoginEvent) -> Unit): Credential? {
        return withContext(Dispatchers.IO) {
            try {
                val raw = SourceManager.callWithEvents("qrLogin", "{}") { json ->
                    parseEvent(json)?.let(onEvent)
                }
                if (raw.isEmpty() || raw == "null") {
                    null
                } else {
                    val dto = SourceDtos.json.decodeFromString(LoginCredDto.serializer(), raw)
                    Credential(
                        musicid = dto.musicid,
                        musickey = dto.musickey,
                        strMusicid = dto.strMusicid,
                        encryptUin = dto.encryptUin,
                        nick = dto.nick,
                        avatarUrl = dto.avatarUrl,
                        createTime = System.currentTimeMillis() / 1000,
                        keyExpiresIn = 0L,
                    )
                }
            } catch (t: Throwable) {
                onEvent(QrLoginEvent.Error(t.message ?: t.toString()))
                null
            }
        }
    }

    /** 源事件 JSON -> 领域事件 */
    private fun parseEvent(json: String): QrLoginEvent? {
        if (json.isEmpty()) return null
        return try {
            val obj = SourceDtos.json.parseToJsonElement(json).let {
                it as? kotlinx.serialization.json.JsonObject
            } ?: return null
            val type = (obj["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
            when (type) {
                "QrReady" -> {
                    val b64 = (obj["b64"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    if (b64.isEmpty()) null
                    else QrLoginEvent.QrReady(java.util.Base64.getDecoder().decode(b64))
                }
                "WaitingScan" -> QrLoginEvent.WaitingScan
                "ScannedConfirm" -> QrLoginEvent.ScannedConfirm
                "Expired" -> QrLoginEvent.Expired
                "Refused" -> QrLoginEvent.Refused
                "Success" -> {
                    val credJson = obj["credential"]?.toString().orEmpty()
                    if (credJson.isEmpty()) null
                    else {
                        val dto = SourceDtos.json.decodeFromString(LoginCredDto.serializer(), credJson)
                        QrLoginEvent.Success(
                            Credential(
                                musicid = dto.musicid,
                                musickey = dto.musickey,
                                strMusicid = dto.strMusicid,
                                encryptUin = dto.encryptUin,
                                nick = dto.nick,
                                avatarUrl = dto.avatarUrl,
                                createTime = System.currentTimeMillis() / 1000,
                            ),
                        )
                    }
                }
                "Error" -> QrLoginEvent.Error(
                    (obj["message"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty(),
                )
                else -> null
            }
        } catch (t: Throwable) {
            QrLoginEvent.Error("事件解析失败: ${t.message}")
        }
    }
}
