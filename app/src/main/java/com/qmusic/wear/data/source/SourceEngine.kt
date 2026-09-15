package com.qmusic.wear.data.source

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 音乐源 JS 插件运行时（Rhino）。
 *
 * APK 只包含通用框架：HTTP / 凭据 / 哈希 / 日志等桥接 API，
 * 全部协议实现（端点、模块、登录链、解析）在下载的源脚本里，
 * 以 `qmu.register({ manifest, handlers })` 方式注册。
 *
 * 线程模型：单线程执行器（Rhino Context 非线程安全）；
 * JS 里的网络请求通过桥接同步阻塞执行，因此源脚本可写成顺序风格。
 */
class SourceEngine(
    private val credentialProvider: () -> CredentialSnapshot,
    eventSink: ((String) -> Unit)? = null,
) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "source-engine").apply { isDaemon = true }
    }
    val dispatcher: CoroutineDispatcher = executor.asCoroutineDispatcher()

    /** 扫码登录等事件回调（调用期间由 SourceManager 挂载） */
    @Volatile
    var eventSink: ((String) -> Unit)? = eventSink

    private val clientFollow = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientNoRedirect = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val guid: String = UUID.randomUUID().toString().replace("-", "").take(32)

    @Volatile
    private var manifestJson: String = "{}"

    private var scope: Scriptable? = null

    /**
     * 在引擎线程加载并执行源脚本，返回 manifest JSON 字符串。
     */
    suspend fun evaluate(script: String): String = withContext(dispatcher) {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1 // Android 禁用字节码生成
            cx.languageVersion = Context.VERSION_ES6
            val s = cx.initStandardObjects()
            scope = s
            ScriptableObject.putProperty(s, "qmu", buildBridge(s))
            cx.evaluateString(s, script, "qmusic_source.js", 1, null)
            manifestJson = cx.evaluateString(s, "JSON.stringify(__source_manifest__ || {})", "manifest", 1, null)
                ?.toString().orEmpty().ifEmpty { "{}" }
            manifestJson
        } finally {
            Context.exit()
        }
    }

    /** 调用源脚本注册的处理器，返回 JSON 字符串（结果为 JSON.stringify 产物） */
    suspend fun call(name: String, argsJson: String): String = withContext(dispatcher) {
        val cx = Context.enter()
        try {
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val s = scope ?: error("音乐源未加载")
            val invoke = cx.evaluateString(
                s,
                "(function(name, argsJson){ return JSON.stringify(__source_handlers__[name](JSON.parse(argsJson))); })",
                "invoke", 1, null,
            ) as BaseFunction
            invoke.call(cx, s, s, arrayOf(name, argsJson))?.toString() ?: "null"
        } finally {
            Context.exit()
        }
    }

    fun manifest(): String = manifestJson

    fun shutdown() {
        executor.shutdown()
    }

    // -----------------------------------------------------------------
    // 桥接 API（注入为全局 qmu 对象）
    // -----------------------------------------------------------------

    private fun buildBridge(scope: Scriptable): NativeObject {
        val qmu = NativeObject()

        fun putFn(name: String, block: (args: Array<Any?>) -> Any?) {
            qmu.put(name, qmu, object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any? = block(args)
            })
        }

        // qmu.http(method, url, headersJson, body, contentType, followRedirects) -> json
        putFn("http") { args ->
            val method = args.getOrNull(0)?.toString() ?: "GET"
            val url = args.getOrNull(1)?.toString().orEmpty()
            val headersJson = args.getOrNull(2)?.toString().orEmpty()
            val body = args.getOrNull(3)?.toString()
            val contentType = args.getOrNull(4)?.toString()
            val follow = (args.getOrNull(5) as? Boolean) ?: true
            httpRequest(method, url, headersJson, body, contentType, follow)
        }

        // qmu.credential() -> json
        putFn("credential") { _ ->
            Jsons.write(credentialProvider())
        }

        // qmu.md5(s) -> hex
        putFn("md5") { args ->
            val input = (args.getOrNull(0)?.toString() ?: "").toByteArray(Charsets.UTF_8)
            val bytes = MessageDigest.getInstance("MD5").digest(input)
            bytes.joinToString("") { "%02x".format(it) }
        }

        // qmu.b64decode(s) -> utf8 字符串（歌词解码等）
        putFn("b64decode") { args ->
            String(Base64.getDecoder().decode(args.getOrNull(0)?.toString().orEmpty()), Charsets.UTF_8)
        }

        // qmu.sleep(ms)：登录轮询等场景的同步等待
        putFn("sleep") { args ->
            Thread.sleep((args.getOrNull(0) as? Number)?.toLong() ?: 1000L)
            null
        }

        // qmu.log(s)
        putFn("log") { args ->
            Log.d("SourceJS", args.getOrNull(0)?.toString().orEmpty())
            null
        }

        // qmu.guid()
        putFn("guid") { _ -> guid }

        // qmu.emit(eventJson)：扫码登录等事件回调
        putFn("emit") { args ->
            eventSink?.invoke(args.getOrNull(0)?.toString().orEmpty())
            null
        }

        // qmu.register({manifest, handlers})：源脚本入口
        putFn("register") { args ->
            val obj = args.getOrNull(0) as? NativeObject ?: return@putFn null
            val manifest = obj.get("manifest", obj)
            val handlers = obj.get("handlers", obj) ?: NativeObject()
            ScriptableObject.putProperty(scope, "__source_manifest__", manifest ?: "{}")
            ScriptableObject.putProperty(scope, "__source_handlers__", handlers)
            null
        }

        return qmu
    }

    /** 同步执行一次 HTTP 请求，返回 {status, location, setCookie, body} JSON */
    private fun httpRequest(
        method: String,
        url: String,
        headersJson: String,
        body: String?,
        contentType: String?,
        followRedirects: Boolean,
    ): String {
        val builder = Request.Builder().url(url)
        Jsons.parseToMap(headersJson).forEach { (k, v) -> builder.header(k, v) }
        when (method.uppercase()) {
            "POST" -> builder.post(
                (body ?: "").toRequestBody(
                    (contentType ?: "application/json; charset=utf-8").toMediaType(),
                ),
            )
            "GET" -> builder.get()
            else -> builder.method(
                method.uppercase(),
                body?.toRequestBody((contentType ?: "text/plain").toMediaType()),
            )
        }
        val client = if (followRedirects) clientFollow else clientNoRedirect
        client.newCall(builder.build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            return "{" +
                "\"status\":" + resp.code + "," +
                "\"location\":" + Jsons.quote(resp.header("Location").orEmpty()) + "," +
                "\"setCookie\":" + Jsons.writeStringList(resp.headers("Set-Cookie")) + "," +
                // body：按 UTF-8 解码（文本响应）；bodyB64：原始字节 base64（二维码等二进制响应）
                "\"body\":" + Jsons.quote(String(bytes, Charsets.UTF_8)) + "," +
                "\"bodyB64\":" + Jsons.quote(Base64.getEncoder().encodeToString(bytes)) +
                "}"
        }
    }
}

/** 轻量 JSON 工具（桥接层专用） */
internal object Jsons {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

    fun quote(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    fun write(any: Any): String = when (any) {
        is CredentialSnapshot -> kotlinx.serialization.json.Json.encodeToString(
            CredentialSnapshot.serializer(), any,
        )
        else -> quote(any.toString())
    }

    fun writeStringList(list: List<String>): String =
        list.joinToString(",", "[", "]") { quote(it) }

    fun parseToMap(s: String): Map<String, String> = runCatching {
        val obj = json.parseToJsonElement(s) as? kotlinx.serialization.json.JsonObject
        obj?.mapValues { (_, v) ->
            (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
        } ?: emptyMap()
    }.getOrDefault(emptyMap())
}

/** 桥接暴露给 JS 的登录凭据快照 */
@kotlinx.serialization.Serializable
data class CredentialSnapshot(
    val musicid: Long = 0L,
    val musickey: String = "",
    val strMusicid: String = "",
    val encryptUin: String = "",
    val nick: String = "",
    val avatarUrl: String = "",
) {
    val isLogged: Boolean get() = musicid != 0L && musickey.isNotEmpty()
}
