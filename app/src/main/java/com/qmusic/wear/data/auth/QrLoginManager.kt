package com.qmusic.wear.data.auth

import com.qmusic.wear.data.api.Credential
import com.qmusic.wear.data.source.SourceDtos
import com.qmusic.wear.data.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 扫码登录方式。
 *
 * 每一种对应源插件的一个 handler：源只实现了 QQ 扫码时，宿主依能力探测隐藏微信入口
 * （酷狗/网易云等源只注册 qrLogin，因此不会看到切换按钮）。
 */
enum class LoginType(val handler: String) {
    /** QQ 互联（ptlogin）扫码，用手机 QQ 扫 */
    QQ("qrLogin"),

    /** 微信开放平台 qrconnect 扫码，用微信「扫一扫」 */
    WECHAT("wxLogin"),
}

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
        val authCookie: String = "",
    )

    /** 该登录方式当前源是否支持（能力探测，未实现则 UI 不展示入口） */
    fun supports(type: LoginType): Boolean = SourceManager.supports(type.handler)

    /**
     * 完整登录流程。成功返回 Credential 并发出 [QrLoginEvent.Success]，
     * 过期/取消/错误只发事件并返回 null（调用方可 retry 重新发起）。
     */
    suspend fun login(type: LoginType = LoginType.QQ, onEvent: (QrLoginEvent) -> Unit): Credential? {
        return withContext(Dispatchers.IO) {
            // 事件由引擎线程投递，用原子标记记录本轮是否收到过事件
            val delivered = AtomicBoolean(false)
            try {
                val raw = SourceManager.callWithEvents(type.handler, "{}") { json ->
                    parseEvent(json)?.let { event ->
                        delivered.set(true)
                        onEvent(event)
                    }
                }
                if (raw.isEmpty() || raw == "null") {
                    // 源未返回凭据且全程没发过任何事件（流程被取消或提前退出）：
                    // 补一个 Error，否则 UI 会停在 Loading 无限转圈
                    if (!delivered.get()) onEvent(QrLoginEvent.Error("登录流程已中断，请重试"))
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
                        authCookie = dto.authCookie,
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

    /**
     * 放弃当前登录（离开登录页 / 切换扫码方式）：
     * 源插件的轮询跑在单线程引擎上，不取消则下一次登录要排队等它跑完。
     */
    fun cancel() {
        SourceManager.cancelCurrentCall()
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
                    // 二选一：b64 = 源直接给的 PNG 字节；text = 二维码内容文本，由宿主渲染成图
                    val text = (obj["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                    when {
                        b64.isNotEmpty() -> QrLoginEvent.QrReady(java.util.Base64.getDecoder().decode(b64))
                        text.isNotEmpty() -> QrRenderer.render(text)?.let { QrLoginEvent.QrReady(it) }
                        else -> null
                    }
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
                                authCookie = dto.authCookie,
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
