package com.clipsync.android

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Mirrors clipsync-desktop's `crypto.rs` exactly, so this app and the Rust
 * daemon derive the same key and produce interoperable ciphertext:
 *   - key = SHA-256(pairing_code.trim())
 *   - fingerprint = first 8 bytes of SHA-256(key || "clipsync-fingerprint"), hex
 *   - encrypted blob = 12-byte random nonce || AES-256-GCM(ciphertext + 16-byte tag)
 */
object Crypto {
    private const val GCM_TAG_BITS = 128
    private const val NONCE_BYTES = 12

    fun deriveKey(pairingCode: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pairingCode.trim().toByteArray(Charsets.UTF_8))
    }

    fun keyFingerprint(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(key)
        digest.update("clipsync-fingerprint".toByteArray(Charsets.UTF_8))
        val hash = digest.digest()
        return hash.copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
    }

    /** Returns nonce || ciphertext, matching the desktop app's wire format. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /** Decrypts a nonce||ciphertext blob produced by [encrypt]. Null on failure (bad key or tampered data). */
    fun decrypt(key: ByteArray, blob: ByteArray): ByteArray? {
        if (blob.size < NONCE_BYTES) return null
        return try {
            val nonce = blob.copyOfRange(0, NONCE_BYTES)
            val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }
}
