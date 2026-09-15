package com.qmusic.wear.data.source

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 音乐源脚本 Ed25519 签名校验。
 *
 * 源脚本首行必须为 `//qmu-sig:v1:<Base64签名>`，签名覆盖其后全部字节；
 * 私钥仅保存在开发者本地（tools/source-signing/），APK 内置公钥。
 * 校验失败（含镜像投毒、传输篡改）一律拒绝执行。
 */
internal object SourceVerifier {

    const val HEADER = "//qmu-sig:v1:"

    /** 内置公钥（SPKI DER Base64），与 tools/source-signing/source_signing_public.key 一致 */
    const val PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAVPTljD4hzk56djqcjEDXDgRFp7E/M018JImhyClj7bA="

    /** 校验脚本签名；返回 null = 通过，非 null = 失败原因 */
    fun verify(script: String, publicKeyB64: String = PUBLIC_KEY_B64): String? {
        if (!script.startsWith(HEADER)) return "缺少签名头"
        val nl = script.indexOf('\n')
        if (nl <= HEADER.length) return "签名格式错误"
        val sigB64 = script.substring(HEADER.length, nl).trim()
        val payload = script.substring(nl + 1)
        return try {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKeyB64)))
            val sig = Signature.getInstance("Ed25519")
            sig.initVerify(key)
            sig.update(payload.toByteArray(Charsets.UTF_8))
            if (sig.verify(Base64.getDecoder().decode(sigB64))) null else "签名不匹配"
        } catch (t: Throwable) {
            "签名校验异常: ${t.message}"
        }
    }
}
