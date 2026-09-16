package com.qmusic.wear

import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * 歌词 handler 集成测试：在 JVM Rhino 环境直接执行仓库源脚本，
 * 用真实 HTTP 验证 lyric 主通道/兜底返回明文 LRC（复现真机「暂无歌词」用）。
 */
class SourceLyricHandlerTest {

    private fun scriptText(): String {
        val candidates = listOf(
            File("../source/qmusic_source.js"),
            File("source/qmusic_source.js"),
        )
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到 source/qmusic_source.js，cwd=" + File(".").absolutePath)
        return f.readText(Charsets.UTF_8)
    }

    private fun makeQmu(scopeRef: Array<Scriptable?>): NativeObject {
        val qmu = NativeObject()
        fun putFn(name: String, block: (Array<Any?>) -> Any?) {
            qmu.put(name, qmu, object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any? = block(args)
            })
        }
        putFn("register") { a ->
            val obj = a.getOrNull(0) as? NativeObject
            val handlers = obj?.get("handlers", obj) ?: NativeObject()
            scopeRef[0]?.let { ScriptableObject.putProperty(it, "__source_handlers__", handlers) }
            null
        }
        putFn("log") { a -> println("SourceJS: " + a.getOrNull(0)); null }
        putFn("credential") { _ ->
            """{"musicid":0,"musickey":"","strMusicid":"0","encryptUin":"","nick":"guest","avatarUrl":""}"""
        }
        putFn("guid") { _ -> "testguid000000000000000000000000" }
        putFn("emit") { _ -> null }
        putFn("sleep") { _ -> null }
        putFn("md5") { a ->
            val md = java.security.MessageDigest.getInstance("MD5")
            md.digest((a.getOrNull(0)?.toString() ?: "").toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        putFn("b64decode") { a ->
            String(
                Base64.getDecoder().decode((a.getOrNull(0)?.toString() ?: "").trim()),
                Charsets.UTF_8,
            )
        }
        putFn("http") { a ->
            val method = a.getOrNull(0)?.toString() ?: "GET"
            val url = a.getOrNull(1)?.toString().orEmpty()
            val headersJson = a.getOrNull(2)?.toString().orEmpty()
            val body = a.getOrNull(3)?.toString()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(headersJson).forEach { m ->
                conn.setRequestProperty(m.groupValues[1], m.groupValues[2])
            }
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            if (body != null && method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            // 与 App 桥接一致：返回 JSON 字符串 {status, body, ...}，由源脚本自行 JSON.parse
            val b64 = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
            "{\"status\":$code,\"location\":\"\",\"setCookie\":[],\"body\":${jsonQuote(text)},\"bodyB64\":\"$b64\"}"
        }
        return qmu
    }

    private fun jsonQuote(s: String): String =
        kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun callHandler(handler: String, argsJson: String): String {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            val scopeRef = arrayOf<Scriptable?>(scope)
            ScriptableObject.putProperty(scope, "qmu", makeQmu(scopeRef))
            cx.evaluateString(scope, scriptText(), "qmusic_source.js", 1, null)
            val invoke = cx.evaluateString(
                scope,
                "(function(name, argsJson){ return JSON.stringify(__source_handlers__[name](JSON.parse(argsJson))); })",
                "invoke", 1, null,
            ) as BaseFunction
            return invoke.call(cx, scope, scope, arrayOf(handler, argsJson))?.toString() ?: "null"
        } finally {
            Context.exit()
        }
    }

    @Test
    fun `lyric handler returns lrc text`() {
        // 周杰伦《晴天》：mid=0039MnYb0qxYhV, songId=97773
        val result = callHandler("lyric", """{"mid":"0039MnYb0qxYhV","songId":97773}""")
        println("lyric result len=" + result.length)
        println("lyric result head=" + result.take(160))
        assertTrue("lyric 不应为空串", result.length > 4)
        // JSON.stringify 后 LRC 内的 [00: 变为 \"[00:（时间戳）或 [ti:（标题）
        assertTrue(
            "返回应包含 LRC 时间戳或标题标记，实际: " + result.take(80),
            result.contains("\\[00:") || result.contains("\\[ti:") ||
                result.contains("[00:") || result.contains("[ti:"),
        )
    }

    @Test
    fun `ping handler works`() {
        val result = callHandler("ping", "{}")
        assertTrue(result.contains("ok"))
    }
}
