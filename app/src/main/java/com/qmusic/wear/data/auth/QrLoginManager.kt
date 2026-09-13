package com.qmusic.wear.data.auth

import com.qmusic.wear.data.api.Credential
import com.qmusic.wear.data.api.QMusicApi
import com.qmusic.wear.util.QmUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.random.Random
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

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
 * QQ 音乐 Web 扫码登录（2026-09 PC 实测链路）：
 *
 * 0. xlogin 预热，建立 ptlogin 会话（拿到 pt_login_sig 等 Cookie）
 * 1. /ssl/ptqrshow 取二维码（qrsig）
 * 2. /ssl/ptqrlogin 轮询（66 未扫 / 67 已扫待确认 / 65 过期 / 0 成功）
 * 3. 成功后跟随 graph.qq.com OAuth 跳转链收集 uin / qqmusic_key
 *
 * 与旧协议的关键差异（均在 PC 抓包验证）：
 * - 主 appid 是 QQ 互联 716027609；100497308 只是 pt_3rd_aid。
 *   旧写法把 100497308 当主 appid 会得到 "appid is invalid"，后续全部 403。
 * - ptqrshow / ptqrlogin 已迁移到 xui.ptlogin2.qq.com/ssl/ 路径，
 *   旧 ssl.ptlogin2.qq.com/ptqrshow 直接 403。
 * - 两个请求都必须带 u1=<s_url>（graph.qq.com/oauth2.0/login_jump），
 *   js_ver 需与官方登录页版本一致，否则轮询报"提交参数错误"。
 */
class QrLoginManager(private val api: QMusicApi) {

    /** ptlogin 域 Cookie（轮询阶段使用） */
    private val ptCookies = linkedMapOf<String, String>()

    /** 站点域 Cookie（OAuth 跳转链收集，最终登录态来源） */
    private val siteCookies = linkedMapOf<String, String>()

    private fun storeCookies(
        map: MutableMap<String, String>,
        response: okhttp3.Response,
    ) {
        response.headers("Set-Cookie").forEach { raw ->
            val first = raw.substringBefore(";").trim()
            val idx = first.indexOf('=')
            if (idx > 0) {
                val name = first.substring(0, idx).trim()
                val value = first.substring(idx + 1).trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    // 服务端用 "deleted" 值清除 Cookie，不能当有效值存
                    if (value.equals("deleted", ignoreCase = true)) map.remove(name)
                    else map[name] = value
                }
            }
        }
    }

    private suspend fun Request_step(
        url: String,
        referer: String,
        cookieMap: Map<String, String>,
    ): okhttp3.Response {
        return api.getRaw(
            url,
            mapOf(
                "Referer" to referer,
                "Cookie" to cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" },
            ),
        )
    }

    /**
     * 完整登录流程。成功返回 Credential 并发出 [QrLoginEvent.Success]，
     * 过期/取消/错误只发事件并返回 null（调用方可 retry 重新发起）。
     *
     * 全程约束在 IO 线程：OkHttp 响应体是懒加载流，
     * body 的读取必须与 execute() 同在 IO，否则触发 NetworkOnMainThreadException。
     */
    suspend fun login(onEvent: (QrLoginEvent) -> Unit): Credential? {
        return withContext(Dispatchers.IO) {
            try {
                ptCookies.clear()
                siteCookies.clear()

                // ---- 0. xlogin 预热：建立会话并取得 pt_login_sig ----
                Request_step(XLOGIN_URL, XLOGIN_URL, ptCookies).use { resp ->
                    if (!resp.isSuccessful) {
                        onEvent(QrLoginEvent.Error("登录初始化失败 HTTP ${resp.code}"))
                        return@withContext null
                    }
                    storeCookies(ptCookies, resp)
                }
                val loginSig = ptCookies["pt_login_sig"].orEmpty()

                // ---- 1. 拉取二维码 ----
                val t = "0.${Random.nextLong(100_000_000_000_000L)}"
                val qrUrl = "https://xui.ptlogin2.qq.com/ssl/ptqrshow" +
                    "?appid=$APPID&e=2&l=M&s=3&d=72&v=4&t=$t" +
                    "&daid=$DAID&pt_3rd_aid=$PT_3RD_AID&u1=${enc(S_URL)}"
                Request_step(qrUrl, XLOGIN_URL, ptCookies).use { resp ->
                    if (!resp.isSuccessful) {
                        onEvent(QrLoginEvent.Error("二维码获取失败 HTTP ${resp.code}"))
                        return@withContext null
                    }
                    storeCookies(ptCookies, resp)
                    val bytes = resp.body?.bytes()
                    if (bytes == null || bytes.isEmpty()) {
                        onEvent(QrLoginEvent.Error("二维码数据为空"))
                        return@withContext null
                    }
                    onEvent(QrLoginEvent.QrReady(bytes))
                }
                val qrsig = ptCookies["qrsig"].orEmpty()

                // ---- 2. 轮询扫码状态 ----
                val ptqrtoken = QmUtils.ptqrtoken(qrsig)
                val pollUrl = "https://xui.ptlogin2.qq.com/ssl/ptqrlogin" +
                    "?u1=${enc(S_URL)}&ptqrtoken=$ptqrtoken&ptredirect=0&h=1&t=1&g=1&from_ui=1" +
                    "&ptlang=2052&action=0-0-${System.currentTimeMillis()}" +
                    "&js_ver=$JS_VER&js_type=1&login_sig=${enc(loginSig)}&pt_uistyle=33" +
                    "&appid=$APPID&daid=$DAID&pt_3rd_aid=$PT_3RD_AID"

                var checkUrl: String? = null
                var nick = ""
                var lastBody = ""
                poll@ while (true) {
                    delay(2000)
                    val body = Request_step(pollUrl, XLOGIN_URL, ptCookies).use { resp ->
                        if (!resp.isSuccessful) {
                            onEvent(QrLoginEvent.Error("登录轮询失败 HTTP ${resp.code}"))
                            return@withContext null
                        }
                        storeCookies(ptCookies, resp)
                        resp.body?.string().orEmpty()
                    }
                    lastBody = body
                    // 形如 ptuiCB('0','0','https://...','0','二维码登录成功。', '昵称'[,'头像'...])
                    // 参数个数不固定：部分版本带头像第 7 参、字段间空格不定、URL 可能含 \/ 转义，
                    // 整体正则容易整体失配（表现为“未取得授权跳转地址”）。
                    // 改为分立提取：code 单独取；成功时第 3 参必为跳转 URL；昵称尽力取最后一参。
                    val code = Regex("""ptuiCB\(\s*'(\d+)""").find(body)?.groupValues?.get(1).orEmpty()
                    when (code) {
                        "0" -> {
                            checkUrl = Regex(
                                """ptuiCB\(\s*'0'\s*,\s*'[^']*'\s*,\s*'([^']+)'""",
                            ).find(body)?.groupValues?.get(1)
                                ?.replace("\\/", "/")
                                .orEmpty()
                            nick = Regex(
                                """ptuiCB\(.*,\s*'([^']*)'\s*\)\s*;?\s*$""",
                            ).find(body)?.groupValues?.get(1).orEmpty()
                            break@poll
                        }
                        "66" -> onEvent(QrLoginEvent.WaitingScan)
                        "67", "68" -> onEvent(QrLoginEvent.ScannedConfirm)
                        "65" -> {
                            onEvent(QrLoginEvent.Expired)
                            return@withContext null
                        }
                        "71", "72", "73" -> {
                            // 提示相关失败，按拒绝处理
                            onEvent(QrLoginEvent.Refused)
                            return@withContext null
                        }
                        else -> {
                            onEvent(QrLoginEvent.Error("登录状态异常 (code=$code)"))
                            return@withContext null
                        }
                    }
                }

                // ---- 3. OAuth 跳转链收集登录 Cookie ----
                if (checkUrl.isNullOrBlank()) {
                    // 带上响应体片段：若再失败可根据真实 ptuiCB 格式继续修正
                    onEvent(QrLoginEvent.Error("未取得授权跳转地址（${lastBody.take(120)}）"))
                    return@withContext null
                }
                var current: String = checkUrl
                var referer: String = XLOGIN_URL
                var lastUrl: String = current
                var lastStatus = 0
                var hops = 0
                redirect@ while (hops < 12) {
                    if (current.isEmpty()) break@redirect
                    // login_jump 是 postMessage 桥接页（实测响应仅 365B）：浏览器流程中
                    // 由 graph authorize 父页 JS 收到 qclogin_success 后 POST authorize
                    // 完成静默授权。这里复刻该 POST（2021 社区实链验证）：
                    // form: from_ptlogin=1 + update_auth=1 + openapi + g_tk(bkn/p_skey) 等，
                    // 302 到 wx_redirect.html?code=...，再由 y.qq.com 服务端种 uin/qm_keyst。
                    if (current.contains("/oauth2.0/login_jump")) {
                        var merged = ptCookies + siteCookies
                        // 先真实请求一次 login_jump（浏览器 iframe 行为，种 graph 会话）
                        Request_step(current, referer, merged).use { resp ->
                            storeCookies(siteCookies, resp)
                            lastUrl = current
                            lastStatus = resp.code
                        }
                        merged = ptCookies + siteCookies
                        val ui = java.util.UUID.randomUUID().toString().replace("-", "")
                        siteCookies["ui"] = ui
                        val pSkey = merged["p_skey"].orEmpty()
                        val authTime = System.currentTimeMillis() / 1000
                        val resp = postForm(
                            AUTHORIZE_URL,
                            S_URL,
                            merged + ("ui" to ui),
                            mapOf(
                                "response_type" to "code",
                                "client_id" to PT_3RD_AID,
                                "redirect_uri" to REDIRECT_URI,
                                "scope" to "",
                                "state" to "state",
                                "switch" to "",
                                "from_ptlogin" to "1",
                                "src" to "1",
                                "update_auth" to "1",
                                "openapi" to "80901010",
                                "g_tk" to QmUtils.gtk(pSkey).toString(),
                                "auth_time" to authTime.toString(),
                                "ui" to ui,
                            ),
                        )
                        storeCookies(siteCookies, resp)
                        lastUrl = AUTHORIZE_URL
                        lastStatus = resp.code
                        val loc = resp.header("Location").orEmpty()
                        referer = AUTHORIZE_URL
                        current = if (resp.isRedirect && loc.isNotBlank()) {
                            resolveUrl(AUTHORIZE_URL, loc)
                        } else {
                            // 200 HTML：提取 JS / meta 跳转兜底
                            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            extractHtmlRedirect(body)?.let { resolveUrl(AUTHORIZE_URL, it) }.orEmpty()
                        }
                        continue
                    }
                    // wx_redirect.html：拿到 code 后由前端 JS POST musicu.fcg
                    // (QQConnectLogin.LoginServer.QQLogin) 换取音乐登录态，Set-Cookie
                    // 种 uin / qm_keyst（页面源码实测，Content-Type 为 form-urlencoded）。
                    if (current.contains("wx_redirect.html") && current.contains("code=")) {
                        val code = Regex("""[?&]code=([^&]+)""")
                            .find(current)?.groupValues?.get(1).orEmpty()
                        val merged = ptCookies + siteCookies
                        val pSkey = merged["p_skey"].orEmpty()
                        val payload =
                            "{\"comm\":{\"g_tk\":${QmUtils.gtk(pSkey)},\"platform\":\"yqq\"," +
                                "\"ct\":24,\"cv\":0}," +
                                "\"req\":{\"module\":\"QQConnectLogin.LoginServer\"," +
                                "\"method\":\"QQLogin\",\"param\":{\"code\":\"$code\"}}}"
                        // 1) 按页面原样 POST（form-urlencoded + JSON body）
                        var ok = false
                        var qqLoginBody = ""
                        try {
                            postRaw(
                                "https://u.y.qq.com/cgi-bin/musicu.fcg",
                                current,
                                merged,
                                payload,
                            ).use { resp ->
                                storeCookies(siteCookies, resp)
                                lastUrl = current
                                lastStatus = resp.code
                                val body = runCatching { resp.body?.string().orEmpty() }
                                    .getOrDefault("")
                                qqLoginBody = body
                                ok = resp.isSuccessful && body.contains("\"code\":0")
                            }
                        } catch (_: Throwable) {
                        }
                        // 2) POST 通道失败时 GET data= 兜底（Set-Cookie 同样生效）
                        if (!ok) {
                            try {
                                val getUrl = "https://u.y.qq.com/cgi-bin/musicu.fcg?data=" +
                                    java.net.URLEncoder.encode(payload, "UTF-8")
                                Request_step(getUrl, current, merged).use { resp ->
                                    storeCookies(siteCookies, resp)
                                    lastUrl = current
                                    lastStatus = resp.code
                                    qqLoginBody = runCatching { resp.body?.string().orEmpty() }
                                        .getOrDefault("")
                                }
                            } catch (_: Throwable) {
                            }
                        }
                        // 提取 encryptUin（收藏歌单接口 CgiGetPlaylistFavInfo 必需）
                        extractEncryptUin(qqLoginBody)?.let { siteCookies["encryptUin"] = it }
                        break@redirect
                    }
                    hops++
                    val merged2 = ptCookies + siteCookies
                    Request_step(current, referer, merged2).use { resp ->
                        storeCookies(siteCookies, resp)
                        lastUrl = current
                        lastStatus = resp.code
                        val loc = resp.header("Location").orEmpty()
                        referer = current
                        current = if (resp.isRedirect && loc.isNotBlank()) {
                            resolveUrl(current, loc)
                        } else {
                            // 200 HTML：提取 JS / meta 跳转兜底（覆盖字面量赋值形式）
                            val body = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                            extractHtmlRedirect(body)?.let { resolveUrl(current, it) }.orEmpty()
                        }
                    }
                }

                val uinRaw = (siteCookies["uin"] ?: ptCookies["uin"]
                    ?: siteCookies["p_uin"] ?: ptCookies["p_uin"])
                    .orEmpty().removePrefix("o")
                val musickey = (siteCookies["qm_keyst"] ?: siteCookies["qqmusic_key"]).orEmpty()
                // encryptUin：QQLogin 响应体 / euin Cookie（收藏歌单接口必需）
                val euin = siteCookies["encryptUin"].orEmpty()
                    .ifEmpty { siteCookies["euin"].orEmpty() }
                if (uinRaw.isEmpty() || musickey.isEmpty()) {
                    // 诊断信息：断点 URL + HTTP 状态 + 已收集 Cookie 名，截图反馈即可定位断在哪一跳
                    val names = siteCookies.keys.joinToString(",")
                    onEvent(
                        QrLoginEvent.Error(
                            "登录 Cookie 不完整" +
                                "（uin${if (uinRaw.isEmpty()) "缺" else "有"}/key${if (musickey.isEmpty()) "缺" else "有"}" +
                                "，终点=$lastUrl HTTP$lastStatus，收集[$names]）",
                        ),
                    )
                    return@withContext null
                }
                val cred = Credential(
                    musicid = uinRaw.toLongOrNull() ?: 0L,
                    musickey = musickey,
                    strMusicid = uinRaw,
                    encryptUin = euin,
                    nick = nick.ifEmpty { siteCookies["nickname"].orEmpty() },
                    avatarUrl = "",
                    createTime = System.currentTimeMillis() / 1000,
                    keyExpiresIn = 0L,
                )
                onEvent(QrLoginEvent.Success(cred))
                cred
            } catch (t: Throwable) {
                onEvent(QrLoginEvent.Error(t.message ?: t.toString()))
                null
            }
        }
    }

    /** 从 QQLogin 响应体提取 encryptUin（收藏歌单 CgiGetPlaylistFavInfo 的 uin 参数） */
    private fun extractEncryptUin(body: String): String? {
        if (body.isBlank()) return null
        val patterns = listOf(
            Regex(""""encrypt_uin"\s*:\s*"([A-Za-z0-9*+=_-]+)""""),
            Regex(""""encryptUin"\s*:\s*"([A-Za-z0-9*+=_-]+)""""),
            Regex(""""euin"\s*:\s*"([A-Za-z0-9*+=_-]+)""""),
            Regex(""""encrypted_user"\s*:\s*"([A-Za-z0-9*+=_-]+)""""),
        )
        for (re in patterns) {
            re.find(body)?.groupValues?.get(1)?.let { if (it.isNotEmpty()) return it }
        }
        return null
    }

    /** 相对地址以 base 为基准解析为绝对 URL（Location / HTML 跳转都可能给相对路径） */
    private fun resolveUrl(base: String, target: String): String = runCatching {
        java.net.URI(base.trim()).resolve(target.trim().replace(" ", "%20")).toString()
    }.getOrDefault(target)

    /** 表单 POST（authorize 静默授权用，浏览器 JS 同构请求） */
    private fun postForm(
        url: String,
        referer: String,
        cookies: Map<String, String>,
        form: Map<String, String>,
    ): okhttp3.Response {
        val body = okhttp3.FormBody.Builder().apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", QMusicApi.WEB_UA)
            .header("Referer", referer)
            .header("Origin", "https://graph.qq.com")
            .header(
                "Cookie",
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            )
            .build()
        return api.http.newCall(req).execute()
    }

    /** 原样 POST（自定义 Content-Type + 字符串 body，复刻 wx_redirect 页面的 XHR） */
    private fun postRaw(
        url: String,
        referer: String,
        cookies: Map<String, String>,
        body: String,
    ): okhttp3.Response {
        val req = okhttp3.Request.Builder()
            .url(url)
            .post(
                body.toRequestBody(
                    "application/x-www-form-urlencoded".toMediaType(),
                ),
            )
            .header("User-Agent", QMusicApi.WEB_UA)
            .header("Referer", referer)
            .header("Origin", "https://y.qq.com")
            .header(
                "Cookie",
                cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            )
            .build()
        return api.http.newCall(req).execute()
    }

    /** 从 HTML 提取 JS / meta refresh 跳转地址（graph.qq.com 登录桥等节点使用） */
    private fun extractHtmlRedirect(html: String): String? {
        if (html.isBlank()) return null
        val patterns = listOf(
            Regex("""location\.replace\(\s*['"]([^'"]+)['"]"""),
            Regex("""(?:top\.|parent\.|window\.|document\.)?location(?:\.href)?\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""http-equiv=["']refresh["'][^>]*content=["'][^"']*?url=([^'"]+)["']"""),
        )
        for (p in patterns) {
            val m = p.find(html)?.groupValues?.getOrNull(1)
            if (!m.isNullOrBlank() && (m.startsWith("http") || m.startsWith("/"))) {
                return m.replace("\\/", "/").replace("&amp;", "&")
            }
        }
        return null
    }

    private companion object {
        /** QQ 互联 OAuth 主 appid（音乐 Web 扫码实际使用） */
        const val APPID = "716027609"

        /** daid（与 appid 配套，固定 383） */
        const val DAID = "383"

        /** 第三方应用 aid（音乐 Web，OAuth 阶段作为 client_id） */
        const val PT_3RD_AID = "100497308"

        /**
         * u1 / s_url：裸 login_jump（对齐浏览器 xlogin 配置，2021 社区实链验证）。
         * 真正的 OAuth 授权由客户端复刻浏览器 JS 的 authorize POST 完成。
         */
        const val S_URL = "https://graph.qq.com/oauth2.0/login_jump"

        /** authorize 静默授权的 POST 端点（form 表单提交） */
        const val AUTHORIZE_URL = "https://graph.qq.com/oauth2.0/authorize"

        /**
         * OAuth 回调（100497308 注册地址）：wx_redirect.html。
         * 旧 wx_open_login.html 版已不匹配，会返回 100010 回调地址不合法。
         */
        const val REDIRECT_URI =
            "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https%3A%2F%2Fy.qq.com%2F%23&use_customer_cb=0"

        /** ptlogin 前端版本（来自官方登录页 ptui.ptui_version，需保持一致） */
        const val JS_VER = "26090116"

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        /** xlogin 预热页地址（同时作为 ptqrshow / ptqrlogin 的合法 Referer） */
        val XLOGIN_URL = "https://xui.ptlogin2.qq.com/cgi-bin/xlogin" +
            "?appid=$APPID&daid=$DAID&style=33&theme=2" +
            "&login_text=%E6%8E%88%E6%9D%83%E5%B9%B6%E7%99%BB%E5%BD%95" +
            "&hide_title_bar=1&hide_border=1&target=self" +
            "&s_url=${enc(S_URL)}&pt_3rd_aid=$PT_3RD_AID"
    }
}
