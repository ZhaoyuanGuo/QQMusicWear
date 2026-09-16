package com.qmusic.wear.data.source

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/**
 * 音乐源脚本 Ed25519 签名校验。
 *
 * 源脚本首行必须为 `//qmu-sig:v1:<Base64签名>`，签名覆盖其后全部字节；
 * 私钥仅保存在开发者本地（tools/source-signing/），APK 内置公钥。
 * 校验失败（含镜像投毒、传输篡改）一律拒绝执行。
 *
 * 实现说明：使用 BouncyCastle 底层 Ed25519Signer（纯 Java、无 JCA Provider 依赖）。
 * Android 部分设备的 Conscrypt 未注册 Ed25519 KeyFactory，JCA 路径会直接抛
 * "Ed25519 KeyFactory not available"，故不使用 java.security。
 */
internal object SourceVerifier {

    const val HEADER = "//qmu-sig:v1:"

    /** 内置公钥（SPKI DER Base64），与 tools/source-signing/source_signing_public.key 一致 */
    const val PUBLIC_KEY_B64 = "MCowBQYDK2VwAyEAVPTljD4hzk56djqcjEDXDgRFp7E/M018JImhyClj7bA="

    /** Ed25519 SPKI DER 的固定 12 字节头（30 2a 30 05 06 03 2b 65 70 03 42 00），其后为 32 字节裸公钥 */
    private const val SPKI_HEADER_LEN = 12

    /** 校验脚本签名；返回 null = 通过，非 null = 失败原因 */
    fun verify(script: String, publicKeyB64: String = PUBLIC_KEY_B64): String? {
        if (!script.startsWith(HEADER)) return "缺少签名头"
        val nl = script.indexOf('\n')
        if (nl <= HEADER.length) return "签名格式错误"
        val sigB64 = script.substring(HEADER.length, nl).trim()
        val payload = script.substring(nl + 1)
        return try {
            val der = Base64.getDecoder().decode(publicKeyB64)
            if (der.size <= SPKI_HEADER_LEN) return "公钥格式错误"
            val rawKey = der.copyOfRange(SPKI_HEADER_LEN, der.size)
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(rawKey, 0))
            verifier.update(payloadBytes, 0, payloadBytes.size)
            val sigBytes = Base64.getDecoder().decode(sigB64)
            if (verifier.verifySignature(sigBytes)) null else "签名不匹配"
        } catch (t: Throwable) {
            "签名校验异常: ${t.message}"
        }
    }

    /**
     * 签名工具链一致性自检（仅单元测试用）：验证「BC 私钥签名 → BC 公钥验签」闭环，
     * 与 JDK 签发的 PKCS8/SPKI 格式兼容。
     */
    internal fun selfTestRoundtrip(pkcs8B64: String, spkiB64: String, data: ByteArray): Boolean {
        return try {
            val pkcs8 = Base64.getDecoder().decode(pkcs8B64)
            if (pkcs8.size <= 16) return false
            // PKCS8 Ed25519 固定 16 字节头（30 2e 02 01 00 30 05 06 03 2b 65 70 04 22 04 20），其后 32 字节裸私钥
            val priv = Ed25519PrivateKeyParameters(pkcs8, 16)
            val signer = Ed25519Signer()
            signer.init(true, priv)
            signer.update(data, 0, data.size)
            val sig = signer.generateSignature()

            val spki = Base64.getDecoder().decode(spkiB64)
            val pub = Ed25519PublicKeyParameters(spki, SPKI_HEADER_LEN)
            val verifier = Ed25519Signer()
            verifier.init(false, pub)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(sig)
        } catch (t: Throwable) {
            false
        }
    }
}
