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

/**
 * qmu.cancelled() 桥接测试（微信扫码登录引入的取消通道）。
 *
 * 登录链跑在源引擎的单线程执行器上：宿主不取消，下一次登录调用就要排队等上一轮
 * 轮询跑完（上限 120 轮 × 数秒）。因此必须验证：
 *   1) 调用进行中 cancelled() 为 false（上一轮遗留的取消标记不能误伤新调用）；
 *   2) cancelCurrentCall() 能让正在轮询的 handler 立刻返回；
 *   3) 桥接返回值在 JS 侧是真正的布尔（用 JSON.stringify 结果断言）。
 */
class SourceCancelBridgeTest {

    private val script = """
        qmu.register({
          manifest: {
            id: 'cancel-test', name: 'cancel-test', themeColor: '#000000',
            version: 1, minAppVersion: 0, playbackHeaders: {}
          },
          handlers: {
            peek: function () { return { cancelled: qmu.cancelled() }; },
            // 模拟登录轮询：每轮开头自查取消标记
            poll: function () {
              for (var i = 0; i < 500; i++) {
                if (qmu.cancelled()) return { stoppedAt: i, cancelled: true };
                qmu.sleep(4);
              }
              return { cancelled: false };
            },
          },
        });
    """.trimIndent()

    @Test
    fun `cancel flag is per call and stops a running poll`() {
        val engine = SourceEngine(credentialProvider = { CredentialSnapshot() })
        try {
            runBlocking {
                engine.evaluate(script)

                // 调用开始时清零，且布尔在 JS 侧保持原样
                val peek = engine.call("peek", "{}")
                assertTrue("未取消时应为 false: $peek", peek.contains("\"cancelled\":false"))

                val running = async(Dispatchers.Default) { engine.call("poll", "{}") }
                delay(60) // 让 poll 真正跑进轮询循环
                engine.cancelCurrentCall()
                val out = withTimeoutOrNull(5_000) { running.await() }
                assertTrue("poll 应因取消提前返回，实际: $out", out != null && out.contains("\"cancelled\":true"))
                val stopped = Regex("\"stoppedAt\":(\\d+)").find(out!!)!!.groupValues[1].toInt()
                assertTrue("应远早于 500 轮退出，实际 $stopped", stopped in 1..400)

                // 取消标记不粘连：下一次调用恢复正常
                assertTrue("新调用应未被取消: $peek", engine.call("peek", "{}").contains("\"cancelled\":false"))
            }
        } finally {
            engine.shutdown()
        }
    }
}
