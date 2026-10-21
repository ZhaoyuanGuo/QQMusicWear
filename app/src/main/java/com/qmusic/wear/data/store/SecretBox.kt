package com.qmusic.wear.data.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 底层加解密原语（可注入，便于 JVM 单测用假实现替换 Keystore）。
 */
interface Cryptor {
    /** 加密：明文 -> 密文（含随机 IV，由实现自行拼接） */
    fun seal(plain: ByteArray): ByteArray

    /** 解密：密文 -> 明文；失败应抛异常，由 [SecretBox] 统一兜底 */
    fun open(sealed: ByteArray): ByteArray
}

/**
 * 敏感值的存储信封：把密文包装成带前缀的可存储字符串，并兼容历史明文。
 *
 * - 新值：`enc:v1:<Base64(iv + 密文)>`
 * - 旧值（无前缀）：原样返回，实现**无感迁移**（下次 save 时自动加密）
 * - 加密失败（Keystore 不可用）：降级存明文，保证登录态不丢
 * - 解密失败（密钥丢失/换机）：返回空串，等价于未登录，绝不抛异常崩溃
 *
 * 格式逻辑与 Keystore 解耦，可在纯 JVM 下测试。
 */
class SecretBox(private val cryptor: Cryptor?) {

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        val c = cryptor ?: return plain
        return runCatching {
            PREFIX + Base64.getEncoder().encodeToString(c.seal(plain.toByteArray(Charsets.UTF_8)))
        }.getOrDefault(plain)
    }

    fun decrypt(stored: String): String {
        if (stored.isEmpty() || !stored.startsWith(PREFIX)) return stored
        val c = cryptor ?: return ""
        return runCatching {
            val sealed = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            String(c.open(sealed), Charsets.UTF_8)
        }.getOrDefault("")
    }

    companion object {
        const val PREFIX = "enc:v1:"

        /** 判定字符串是否为已加密信封（供迁移与诊断） */
        fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
    }
}

/**
 * Android Keystore 实现的 AES-GCM 加解密（密钥不出安全硬件/系统密钥库）。
 * minSdk 27，Keystore 与 GCM 均可用；不可用时由 [SecretBox] 降级为明文。
 */
class AndroidKeystoreCryptor : Cryptor {

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        // 前置 12 字节 GCM IV，解密时按固定长度切分
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= GCM_IV_LEN) error("密文长度非法")
        val iv = sealed.copyOfRange(0, GCM_IV_LEN)
        val body = sealed.copyOfRange(GCM_IV_LEN, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "qmusic_cred_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LEN = 12
        const val GCM_TAG_BITS = 128
    }
}
