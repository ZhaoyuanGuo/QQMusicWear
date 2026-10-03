package com.qmusic.wear

import com.qmusic.wear.data.source.CredentialSnapshot
import com.qmusic.wear.data.source.SourceEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.zip.Deflater

/**
 * 桥接原语测试：在 JVM Rhino 环境直接调用 [SourceEngine] 注入的 qmu.* 原语，
 * 验证 aesCbcHex/aesCbcDecryptB64 回环、rsaNoPadHex 定长输出、inflateB64 解压。
 */
class SourceEngineBridgeTest {

    private fun deflateB64(text: String): String {
        val deflater = Deflater()
        deflater.setInput(text.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** 执行一段 JS 并把结果通过 manifest 回传（evaluate 返回 manifest JSON） */
    private fun runBridgeScript(body: String): String {
        val engine = SourceEngine(credentialProvider = { CredentialSnapshot() })
        return try {
            runBlocking {
                engine.evaluate(
                    """
                    var __r = {};
                    $body
                    qmu.register({ manifest: {
                      id: 'bridge-test', name: 'bridge-test', themeColor: '#000000',
                      version: 1, minAppVersion: 0, playbackHeaders: {},
                      enc: __r.enc, dec: __r.dec, rsaLen: __r.rsaLen, inf: __r.inf
                    }, handlers: {} });
                    """.trimIndent(),
                )
            }
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun `aes cbc encrypt decrypt round trip`() {
        val key = "0CoJUm6Qyw8W8jud"
        val iv = "0102030405060708"
        val plain = "hello world"
        // 先用 Kotlin 侧已知明文算出 hex，再让 JS 解密，验证两个原语一致
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.ISO_8859_1), "AES"),
            javax.crypto.spec.IvParameterSpec(iv.toByteArray(Charsets.ISO_8859_1)),
        )
        val b64 = Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))

        val manifest = runBridgeScript(
            """
            __r.enc = qmu.aesCbcHex('$plain', '$key', '$iv');
            __r.dec = qmu.aesCbcDecryptB64('$b64', '$key', '$iv');
            __r.rsaLen = 0;
            __r.inf = '';
            """.trimIndent(),
        )
        assertTrue("dec 应为 hello world，实际: $manifest", manifest.contains("\"dec\":\"$plain\""))
        // 11 字节明文 PKCS7 填充到 16 字节 → 32 位 hex
        assertTrue("enc 应为 32 位 hex", Regex("\"enc\":\"[0-9a-f]{32}\"").containsMatchIn(manifest))
    }

    @Test
    fun `rsa no pad returns fixed width hex for 128 byte plaintext`() {
        val manifest = runBridgeScript(
            """
            var pad = '';
            for (var i = 0; i < 112; i++) pad += String.fromCharCode(0);
            __r.enc = '';
            __r.dec = '';
            __r.rsaLen = qmu.rsaNoPadHex(pad + 'ABCDEFGHIJKLMNOP', '${"F".repeat(256)}', '10001').length;
            __r.inf = '';
            """.trimIndent(),
        )
        assertTrue("rsaLen 应为 256，实际: $manifest", manifest.contains("\"rsaLen\":256"))
    }

    @Test
    fun `inflate b64 decompresses zlib payload`() {
        val text = "hello-inflate"
        val b64 = deflateB64(text)
        val manifest = runBridgeScript(
            """
            __r.enc = '';
            __r.dec = '';
            __r.rsaLen = 0;
            __r.inf = qmu.inflateB64('$b64');
            """.trimIndent(),
        )
        assertTrue("inf 应为 $text，实际: $manifest", manifest.contains("\"inf\":\"$text\""))
    }
}