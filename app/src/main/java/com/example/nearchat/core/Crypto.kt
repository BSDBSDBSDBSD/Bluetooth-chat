package com.example.nearchat.core

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives used by NearChat.
 *
 * - Identity: ECDH P-256 key pair per install.
 * - Private chats: ECDH shared secret -> HKDF-SHA256 -> AES-256-GCM key (end-to-end,
 *   relays that forward the packet cannot read it).
 * - Groups: random AES-256 key created by the group creator and delivered to every
 *   member inside a pairwise-encrypted invite.
 */
object Crypto {
    private val random = SecureRandom()
    private const val GCM_IV = 12
    private const val GCM_TAG_BITS = 128

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun newIdentityKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    fun publicKeyFromB64(s: String): PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(unb64(s)))
    fun privateKeyFromBytes(b: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(b))

    fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(data) }

    /** RFC 5869 HKDF with SHA-256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** Derives the pairwise chat key between two identities (same result on both sides). */
    fun pairwiseKey(myPriv: PrivateKey, myId: String, theirPubB64: String, theirId: String): ByteArray {
        val shared = ecdh(myPriv, publicKeyFromB64(theirPubB64))
        val ids = listOf(myId, theirId).sorted().joinToString("|")
        return hkdf(shared, sha256(ids.toByteArray()), "nearchat-v1-pairwise".toByteArray())
    }

    /** AES-256-GCM. Output = IV(12) || ciphertext+tag. */
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val iv = randomBytes(GCM_IV)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        if (aad != null) c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Throws on tampering / wrong key. */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        require(sealed.size > GCM_IV + 16) { "ciphertext too short" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, sealed, 0, GCM_IV))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(sealed, GCM_IV, sealed.size - GCM_IV)
    }

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /**
     * A "safety number" both users can compare in person: identical on both phones
     * only if nobody tampered with the key exchange.
     */
    fun safetyNumber(pubA: String, pubB: String): String {
        val digest = sha256(listOf(pubA, pubB).sorted().joinToString("|").toByteArray())
        val sb = StringBuilder()
        for (i in 0 until 12) {
            val v = ((digest[i * 2].toInt() and 0xff) shl 8) or (digest[i * 2 + 1].toInt() and 0xff)
            sb.append((v % 100000).toString().padStart(5, '0'))
            if (i != 11) sb.append(if (i % 4 == 3) "\n" else " ")
        }
        return sb.toString()
    }

    fun shortFingerprint(pub: String): String =
        sha256(unb64(pub)).take(6).joinToString(":") { "%02X".format(it.toInt() and 0xff) }
}
