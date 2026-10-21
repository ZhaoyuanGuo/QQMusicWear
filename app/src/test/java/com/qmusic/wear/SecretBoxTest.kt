package com.qmusic.wear

import com.qmusic.wear.data.store.Cryptor
import com.qmusic.wear.data.store.SecretBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * [SecretBox] 凭据信封测试：加密往返、历史明文无感迁移、
 * 密文损坏/密钥丢失时安全降级（返回空串而非崩溃）。
 */
class SecretBoxTest {

    /** 用可逆的字节取反模拟 AES：确定性、无需 Keystore，纯 JVM 可跑 */
    private class FakeCryptor : Cryptor {
        override fun seal(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun open(sealed: ByteArray): ByteArray = sealed.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    /** 解密必失败的实现，模拟换机后 Keystore 密钥丢失 */
    private class BrokenCryptor : Cryptor {
        override fun seal(plain: ByteArray): ByteArray = plain
        override fun open(sealed: ByteArray): ByteArray = error("key lost")
    }

    @Test
    fun `encrypt then decrypt round trip`() {
        val box = SecretBox(FakeCryptor())
        val secret = "abc-会话密钥-123"
        val stored = box.encrypt(secret)
        assertTrue("落盘值应带加密前缀", SecretBox.isEncrypted(stored))
        assertFalse("密文不应包含明文", stored.contains(secret))
        assertEquals(secret, box.decrypt(stored))
    }

    @Test
    fun `legacy plaintext value is read as-is`() {
        val box = SecretBox(FakeCryptor())
        // 历史版本直接存明文，无前缀
        assertEquals("legacy-key", box.decrypt("legacy-key"))
    }

    @Test
    fun `empty value stays empty`() {
        val box = SecretBox(FakeCryptor())
        assertEquals("", box.encrypt(""))
        assertEquals("", box.decrypt(""))
    }

    @Test
    fun `no cryptor degrades to plaintext`() {
        val box = SecretBox(null)
        assertEquals("plain", box.encrypt("plain"))
        assertEquals("plain", box.decrypt("plain"))
    }

    @Test
    fun `decrypt failure returns empty instead of crashing`() {
        val box = SecretBox(BrokenCryptor())
        val stored = SecretBox.PREFIX + Base64.getEncoder().encodeToString("whatever".toByteArray())
        assertEquals("", box.decrypt(stored))
    }

    @Test
    fun `corrupted ciphertext returns empty`() {
        val box = SecretBox(FakeCryptor())
        assertEquals("", box.decrypt(SecretBox.PREFIX + "!!!not-base64!!!"))
    }
}
