package com.qmusic.wear

import com.qmusic.wear.data.source.SourceVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** 音乐源签名校验契约测试 */
class SourceVerifierTest {

    /** 用临时密钥对走完整「签名 → 校验」链路（不依赖真实私钥） */
    @Test
    fun `signed script passes verification`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)

        val payload = "var SOURCE_VERSION = 1;\nqmu.register({});\n"
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(payload.toByteArray(Charsets.UTF_8))
        }.sign()
        val script = SourceVerifier.HEADER +
            Base64.getEncoder().encodeToString(sig) + "\n" + payload

        assertNull(SourceVerifier.verify(script, pubB64))
    }

    @Test
    fun `tampered payload fails`() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)

        val payload = "qmu.register({});\n"
        val sig = Signature.getInstance("Ed25519").apply {
            initSign(kp.private)
            update(payload.toByteArray(Charsets.UTF_8))
        }.sign()
        val script = SourceVerifier.HEADER +
            Base64.getEncoder().encodeToString(sig) + "\n" + payload + "// 篡改\n"

        assertEquals("签名不匹配", SourceVerifier.verify(script, pubB64))
    }

    @Test
    fun `missing or malformed header rejected`() {
        assertNotNull(SourceVerifier.verify("qmu.register({});\n"))
        assertNotNull(SourceVerifier.verify(SourceVerifier.HEADER))
        assertNotNull(SourceVerifier.verify(SourceVerifier.HEADER + "\nbody"))
    }

    @Test
    fun `bundled public key is valid spki`() {
        // APK 内置公钥必须可解析（防手滑改坏）
        val key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
            java.security.spec.X509EncodedKeySpec(
                Base64.getDecoder().decode(SourceVerifier.PUBLIC_KEY_B64),
            ),
        )
        assertTrue(key.algorithm.uppercase().startsWith("ED"))
        // 用内置公钥校验任意脚本应得到确定性失败而非异常路径
        assertNotNull(SourceVerifier.verify("no signature"))
    }

    @Test
    fun `pkcs8 roundtrip accepted by keyfactory`() {
        // 与工具链一致：私钥按 PKCS8 存取（确保工具生成的格式与 JDK 兼容）
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val restored = java.security.KeyFactory.getInstance("Ed25519").generatePrivate(
            PKCS8EncodedKeySpec(kp.private.encoded),
        )
        val pub = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(kp.public.encoded),
        )
        val data = "roundtrip".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("Ed25519").apply { initSign(restored); update(data) }.sign()
        val ok = Signature.getInstance("Ed25519").apply { initVerify(pub); update(data) }.verify(sig)
        assertTrue(ok)
    }
}
