package com.qmusic.wear

import com.qmusic.wear.data.source.CredentialSnapshot
import com.qmusic.wear.data.source.SourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * qmu.emit 事件回调的按调用绑定测试（扫码登录「二维码一直转圈不出现」的回归）。
 *
 * 源引擎是单线程串行执行调用的，但调用是排队的：若事件回调在调用方线程就写进引擎的
 * 共享槽位、结束时再无条件清空，那么登录链在跑的同时任何一次业务调用都会
 * 把回调换走并置空，QrReady 就静默丢了。QQ/微信取码要 2~3 次跨境请求（窗口宽）必输，
 * 酷狗一次境内请求（窗口窄）几乎必赢——正是线上现象的差异来源。
 *
 * 因此约定：回调随调用下发，绑定与还原都发生在引擎线程上，且只对本调用生效。
 */
class SourceEventSinkTest {

    private val script = """
        qmu.register({
          manifest: {
            id: 'sink-test', name: 'sink-test', themeColor: '#000000',
            version: 1, minAppVersion: 0, playbackHeaders: {}
          },
          handlers: {
            // 模拟扫码登录：取码前有几步网络往返（用 sleep 代位），期间分两轮回事件
            qrLogin: function () {
              qmu.sleep(160);
              qmu.emit(JSON.stringify({ type: 'QrReady' }));
              qmu.sleep(60);
              qmu.emit(JSON.stringify({ type: 'WaitingScan' }));
              return null;
            },
            // 模拟首页/资料等业务调用：它自己也要发事件（凭据过期）
            biz: function () {
              qmu.emit(JSON.stringify({ type: 'CredentialExpired' }));
              return { ok: 1 };
            },
            // 模拟未注册回调的调用：事件应被丢弃，不得回灌给上一个调用的 sink
            orphan: function () {
              qmu.emit(JSON.stringify({ type: 'Stray' }));
              return { ok: 1 };
            },
          },
        });
    """.trimIndent()

    @Test
    fun `并发业务调用不得抢走登录调用的事件回调`() {
        val engine = SourceEngine(credentialProvider = { CredentialSnapshot() })
        try {
            runBlocking {
                engine.evaluate(script)

                val loginEvents = CopyOnWriteArrayList<String>()
                val bizEvents = CopyOnWriteArrayList<String>()

                val login = async(Dispatchers.Default) {
                    engine.call("qrLogin", "{}") { loginEvents.add(it) }
                }
                delay(40) // 让登录链真正跑进「取码前」的窗口
                val biz = withTimeoutOrNull(5_000) { engine.call("biz", "{}") { bizEvents.add(it) } }

                val out = withTimeoutOrNull(5_000) { login.await() }
                assertTrue("业务调用应正常返回: $biz", biz != null && biz.contains("\"ok\":1"))
                assertTrue("登录调用应正常返回: $out", out == "null")

                // 核心断言：登录的两段事件都完整送达自己的回调，没被并发调用换走/清空
                assertTrue("QrReady 应送达登录回调: $loginEvents",
                    loginEvents.any { it.contains("QrReady") })
                assertTrue("WaitingScan 应送达登录回调: $loginEvents",
                    loginEvents.any { it.contains("WaitingScan") })
                assertEquals("登录事件应只收到这两条: $loginEvents", 2, loginEvents.size)
                assertTrue("业务事件应送达业务回调: $bizEvents",
                    bizEvents.any { it.contains("CredentialExpired") })

                // 调用结束后回调必须还原：无回调调用里的事件不得再打到登录的 sink
                engine.call("orphan", "{}")
                assertEquals("结束后不应再收到事件: $loginEvents", 2, loginEvents.size)
            }
        } finally {
            engine.shutdown()
        }
    }
}
