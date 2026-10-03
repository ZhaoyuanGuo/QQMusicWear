package com.qmusic.wear.data.qplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP/1.1 服务器，专为 QPlay/DLNA 渲染器定制：
 * - 支持任意方法（含 UPnP GENA 的 SUBSCRIBE / UNSUBSCRIBE，标准库 HttpServer 不支持）
 * - 按字节解析（头部 ASCII + UTF-8 请求体，QQ 音乐控制点的 SOAP 里含中文曲目名）
 * - 应答统一 Connection: close，一连接一请求，规避 keep-alive 状态管理
 *
 * 协议交互形态已在 Phase 0 抓包验证（tools/qplay_probe/QPlayProbe.js 同构实现）。
 */
class MinimalHttpServer(
    private val port: Int,
    private val handler: suspend (Request) -> Response,
) {
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
        val remoteAddress: String,
    ) {
        /** 大小写不敏感取头 */
        fun header(name: String): String? = headers[name.lowercase()]
    }

    data class Response(
        val status: Int = 200,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
    ) {
        companion object {
            fun text(xml: String, status: Int = 200, headers: Map<String, String> = emptyMap()) =
                Response(status, headers, xml.toByteArray(Charsets.UTF_8))
        }
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                val ss = ServerSocket(port)
                serverSocket = ss
                while (running.get()) {
                    val socket = ss.accept()
                    scope.launch { handle(socket) }
                }
            } catch (_: Throwable) {
                // 正常 stop 会触发 socket close 异常，静默退出
            }
        }, "QPlayHttp").apply { isDaemon = true }.start()
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
    }

    private suspend fun handle(socket: Socket) {
        try {
            socket.soTimeout = 10_000
            socket.use { s ->
                val input = s.getInputStream()
                val headBytes = readUntil(input, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)) ?: return
                val head = String(headBytes, Charsets.ISO_8859_1)
                val lines = head.split("\r\n")
                if (lines.isEmpty()) return
                val parts = lines[0].split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]

                val headers = mutableMapOf<String, String>()
                for (line in lines.drop(1)) {
                    val idx = line.indexOf(':')
                    if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }

                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val body = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                } else ""

                val response = withContext(Dispatchers.IO) {
                    runCatching {
                        handler(Request(method, path, headers, body, s.remoteSocketAddress?.toString() ?: ""))
                    }.getOrElse { t ->
                        android.util.Log.e("QPlayHttp", "handler error", t)
                        Response.text("", 500)
                    }
                }

                val out = BufferedOutputStream(s.getOutputStream())
                val headText = buildString {
                    append("HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n")
                    append("SERVER: QQMusicWear/1.0 UPnP/1.0\r\n")
                    response.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                    if (method != "HEAD") append("CONTENT-LENGTH: ${response.body.size}\r\n")
                    append("CONNECTION: close\r\n\r\n")
                }
                out.write(headText.toByteArray(Charsets.ISO_8859_1))
                out.write(response.body)
                out.flush()
            }
        } catch (_: Throwable) {
            // 客户端断开等 IO 异常静默
        }
    }

    /** 从流中持续读取，直到出现指定分隔符字节序列；返回包含分隔符的全部字节 */
    private fun readUntil(input: InputStream, delim: ByteArray): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(1)
        var match = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) return null
            val b = buf[0]
            out.write(b.toInt())
            match = if (b == delim[match]) {
                if (match + 1 == delim.size) return out.toByteArray()
                match + 1
            } else if (b == delim[0]) 1 else 0
            if (out.size() > 1024 * 1024) return null // 头部异常保护
        }
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
