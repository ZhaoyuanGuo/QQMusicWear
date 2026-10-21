package com.qmusic.wear

import com.qmusic.wear.data.source.CredentialSnapshot
import com.qmusic.wear.data.source.SourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * cancelCurrentCall() 必须掐断在飞的 HTTP 请求（离开登录页后重进要等 20 多秒的回归）。
 *
 * 登录轮询跑在源引擎的单线程上，取消标记只能靠 handler 每轮开头自查生效；
 * 而微信长轮询的一次请求最长会占满 readTimeout(20s)/callTimeout(30s)——
 * 只置标记不掐请求的话，用户退出登录页再进来，新调用仍要排在这次挂死的请求后面干等超时。
 */
class SourceHttpCancelTest {

    /** 只接受连接、永不回包的本地端口：让 qmu.http 一直挂在读响应上 */
    private class SilentServer {
        val socket = ServerSocket(0)
        val port = socket.localPort
        private val held = CopyOnWriteArrayList<java.net.Socket>()
        private val acceptor = Thread {
            runCatching {
                while (!socket.isClosed) {
                    held.add(socket.accept())
                }
            }
        }

        init {
            acceptor.isDaemon = true
            acceptor.start()
        }

        fun close() {
            held.forEach { runCatching { it.close() } }
            runCatching { socket.close() }
        }
    }

    private fun script(port: Int) = """
        qmu.register({
          manifest: {
            id: 'http-cancel-test', name: 'http-cancel-test', themeColor: '#000000',
            version: 1, minAppVersion: 0, playbackHeaders: {}
          },
          handlers: {
            // 模拟长轮询里的一次请求：不掐断就要等它自己超时才返回
            pollOnce: function () {
              var r = qmu.http('GET', 'http://127.0.0.1:$port/', '{}', null, null, false);
              return { status: JSON.parse(r).status };
            },
            peek: function () { return { alive: 1 }; },
          },
        });
    """.trimIndent()

    @Test
    fun `取消当前调用应立即掐断正在飞行的 HTTP 请求`() {
        val server = SilentServer()
        val engine = SourceEngine(credentialProvider = { CredentialSnapshot() })
        try {
            runBlocking {
                engine.evaluate(script(server.port))

                val running = async(Dispatchers.Default) { engine.call("pollOnce", "{}") }
                delay(800) // 让请求真正发出去并挂在读响应上
                val t0 = System.currentTimeMillis()
                engine.cancelCurrentCall()

                val out = withTimeoutOrNull(5_000) { running.await() }
                val cost = System.currentTimeMillis() - t0
                assertTrue("取消后应立即返回，而不是等超时，实际: $out", out != null && out.contains("\"status\":0"))
                assertTrue("返回应远早于 readTimeout(20s)，实际耗时 ${cost}ms", cost < 3_000)

                // 引擎随即空出来：下一次调用不必排队
                val peek = withTimeoutOrNull(3_000) { engine.call("peek", "{}") }
                assertTrue("取消后下一次调用应立即执行，实际: $peek", peek != null && peek.contains("\"alive\":1"))
            }
        } finally {
            server.close()
            engine.shutdown()
        }
    }
}
