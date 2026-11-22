package io.github.jerryskywalker.tmcomp.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class DeviceStorage(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("companion_device", Context.MODE_PRIVATE)
    val installationId: String
        get() = synchronized(lock) {
            preferences.getString("installation", null) ?: UUID.randomUUID().toString().also {
                preferences.edit().putString("installation", it).commit()
            }
        }

    inline fun <reified T> read(key: String): T? = preferences.getString(key, null)?.let {
        runCatching { wireJson.decodeFromString<T>(it) }.getOrNull()
    }

    inline fun <reified T> write(key: String, value: T?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, wireJson.encodeToString(value))
        editor.apply()
    }

    fun credentials(): Credentials? = synchronized(lock) {
        val blob = preferences.getString("credential", null) ?: return null
        runCatching {
            val packed = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            wireJson.decodeFromString<Credentials>(cipher.doFinal(packed.copyOfRange(12, packed.size)).decodeToString())
        }.getOrNull()
    }

    fun saveCredentials(value: Credentials?) = synchronized(lock) {
        val editor = preferences.edit()
        if (value == null) {
            check(editor.remove("credential").commit()) { "无法清除设备授权" }
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(wireJson.encodeToString(value).encodeToByteArray())
            check(editor.putString("credential", Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)).commit()) {
                "无法保存设备授权"
            }
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "companion-gateway-aes"
        val lock = Any()
    }
}
