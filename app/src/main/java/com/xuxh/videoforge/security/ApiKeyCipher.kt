package com.xuxh.videoforge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 使用 Android Keystore 的 AES-GCM 对 API Key 做加密。
 * 密钥由系统生成并受硬件/系统保护，明文不落盘（对应 README 安全边界声明）。
 */
object ApiKeyCipher {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "videoforge_api_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128

    /** 加密并返回 Base64(NO_WRAP) 密文（iv + ciphertext）；空输入或失败返回 null。 */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return null
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /** 解密上述密文；密文损坏时返回 null（调用方视为无 Key）。 */
    fun decrypt(encoded: String): String? {
        if (encoded.isEmpty()) return null
        return try {
            val key = getOrCreateKey()
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            if (blob.size <= IV_LENGTH) return null
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val encrypted = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}