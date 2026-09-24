package com.example.nearchat.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted local storage.
 *
 * A random 256-bit data key (DEK) encrypts every file (AES-GCM). The DEK itself is
 * stored wrapped by a non-exportable AES key that lives in the Android Keystore
 * (hardware-backed on most phones), so files copied off the device are unreadable.
 */
class SecureStore(context: Context) {
    private val dir = File(context.filesDir, "secure").apply { mkdirs() }
    val mediaDir = File(dir, "media").apply { mkdirs() }
    private val dek: ByteArray = loadOrCreateDek()

    private fun keystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        try {
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (e: Exception) {
            // Broken/unrecoverable entry (seen after OS updates on some phones): replace it
            // instead of crashing on every launch. loadOrCreateDek() then resets storage.
            Log.e(TAG, "Keystore key unreadable, recreating", e)
            try { ks.deleteEntry(KEY_ALIAS) } catch (_: Exception) {}
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun loadOrCreateDek(): ByteArray {
        val f = File(dir, "dek.bin")
        val key = keystoreKey()
        if (f.exists()) {
            try {
                val data = f.readBytes()
                val ivLen = data[0].toInt()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data, 1, ivLen))
                return c.doFinal(data, 1 + ivLen, data.size - 1 - ivLen)
            } catch (e: Exception) {
                // Keystore key lost (e.g. restored backup) - old data cannot be recovered.
                Log.e(TAG, "Cannot unwrap data key, resetting local storage", e)
                dir.listFiles()?.forEach { if (it.isFile) it.delete() }
                mediaDir.listFiles()?.forEach { it.delete() }
            }
        }
        val newDek = Crypto.randomBytes(32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key) // keystore picks the IV
        val ct = c.doFinal(newDek)
        val iv = c.iv
        writeAtomic(f, byteArrayOf(iv.size.toByte()) + iv + ct)
        return newDek
    }

    private fun writeAtomic(f: File, bytes: ByteArray) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun write(name: String, plain: ByteArray) = writeAtomic(File(dir, name), Crypto.seal(dek, plain, name.toByteArray()))

    fun read(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try { Crypto.open(dek, f.readBytes(), name.toByteArray()) } catch (e: Exception) {
            Log.e(TAG, "Corrupted file $name", e); null
        }
    }

    fun delete(name: String) { File(dir, name).delete() }

    fun writeText(name: String, s: String) = write(name, s.toByteArray(Charsets.UTF_8))
    fun readText(name: String): String? = read(name)?.toString(Charsets.UTF_8)

    fun list(prefix: String): List<String> = dir.list()?.filter { it.startsWith(prefix) && !it.endsWith(".tmp") } ?: emptyList()

    // ---- media ----
    private fun mediaName(id: String) = id.filter { it.isLetterOrDigit() || it == '-' } + ".bin"

    fun writeMedia(id: String, bytes: ByteArray) {
        val name = mediaName(id)
        writeAtomic(File(mediaDir, name), Crypto.seal(dek, bytes, name.toByteArray()))
    }

    fun readMedia(id: String): ByteArray? {
        val name = mediaName(id)
        val f = File(mediaDir, name)
        if (!f.exists()) return null
        return try { Crypto.open(dek, f.readBytes(), name.toByteArray()) } catch (e: Exception) { null }
    }

    fun deleteMedia(id: String) { File(mediaDir, mediaName(id)).delete() }

    companion object {
        private const val TAG = "SecureStore"
        private const val KEY_ALIAS = "nearchat_storage_key"
    }
}
