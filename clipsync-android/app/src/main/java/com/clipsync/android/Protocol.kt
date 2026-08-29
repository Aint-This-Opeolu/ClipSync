package com.clipsync.android

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Handshake message, sent unencrypted first, matching protocol.rs's `Hello`. */
data class Hello(val deviceName: String, val keyFingerprint: String)

/** A clipboard update, sent encrypted after handshake, matching protocol.rs's `ClipUpdate`. */
data class ClipUpdate(val text: String, val fromDevice: String)

private const val MAX_FRAME = 16 * 1024 * 1024

/**
 * Mirrors clipsync-desktop's `protocol.rs`: a 4-byte big-endian length prefix
 * followed by the payload (JSON for Hello, encrypted bytes for ClipUpdate).
 */
object Protocol {

    fun writeFrame(out: OutputStream, payload: ByteArray) {
        val dos = DataOutputStream(out)
        dos.writeInt(payload.size) // Int is written big-endian, matching Rust's to_be_bytes
        dos.write(payload)
        dos.flush()
    }

    /** Reads a length-prefixed frame. Returns null on a clean connection close. */
    fun readFrame(input: InputStream): ByteArray? {
        val dis = DataInputStream(input)
        val len = try {
            dis.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len < 0 || len > MAX_FRAME) throw IOException("frame too large")
        val buf = ByteArray(len)
        dis.readFully(buf)
        return buf
    }

    fun sendHello(out: OutputStream, hello: Hello) {
        val json = JSONObject()
            .put("device_name", hello.deviceName)
            .put("key_fingerprint", hello.keyFingerprint)
        writeFrame(out, json.toString().toByteArray(Charsets.UTF_8))
    }

    fun readHello(input: InputStream): Hello? {
        val bytes = readFrame(input) ?: return null
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            Hello(json.getString("device_name"), json.getString("key_fingerprint"))
        } catch (e: Exception) {
            null
        }
    }

    fun encodeClipUpdate(update: ClipUpdate): ByteArray {
        val json = JSONObject()
            .put("text", update.text)
            .put("from_device", update.fromDevice)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun decodeClipUpdate(bytes: ByteArray): ClipUpdate? {
        return try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            ClipUpdate(json.getString("text"), json.getString("from_device"))
        } catch (e: Exception) {
            null
        }
    }
}
