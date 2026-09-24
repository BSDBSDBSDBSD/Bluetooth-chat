package com.example.nearchat.net

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID

/**
 * Wire packet. Frame layout on the socket:
 *   int MAGIC | int headerLen | header (UTF-8 JSON) | int bodyLen | body
 *
 * The header (routing metadata) is plaintext so that intermediate phones can relay
 * packets. The body of private/group packets is AES-GCM encrypted end-to-end.
 */
class Packet(
    val id: String,
    val kind: String,
    val from: String,
    val to: String,
    val ttl: Int,
    val ts: Long,
    val meta: JSONObject,
    val body: ByteArray,
) {
    fun withTtl(newTtl: Int) = Packet(id, kind, from, to, newTtl, ts, meta, body)

    /** Additional authenticated data that binds the encrypted body to its routing header. */
    fun aad(): ByteArray = "$id|$kind|$from|$to".toByteArray(Charsets.UTF_8)

    fun encode(): ByteArray {
        val header = JSONObject()
            .put("id", id).put("k", kind).put("f", from).put("t", to).put("ttl", ttl).put("ts", ts).put("m", meta)
            .toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(16 + header.size + body.size)
        DataOutputStream(bos).apply {
            writeInt(MAGIC); writeInt(header.size); write(header); writeInt(body.size); write(body); flush()
        }
        return bos.toByteArray()
    }

    companion object {
        const val MAGIC = 0x4E434831 // "NCH1"
        const val MAX_HEADER = 64 * 1024
        const val MAX_BODY = 16 * 1024 * 1024

        const val KIND_ANNOUNCE = "ann"
        const val KIND_SECURE = "sec"
        const val KIND_GROUP = "grp"
        const val BROADCAST = "*"
        const val ANNOUNCE_TTL = 5
        const val DEFAULT_TTL = 6

        fun create(kind: String, from: String, to: String, meta: JSONObject = JSONObject(), body: ByteArray = ByteArray(0), ttl: Int = DEFAULT_TTL) =
            Packet(UUID.randomUUID().toString(), kind, from, to, ttl, System.currentTimeMillis(), meta, body)

        fun read(input: DataInputStream): Packet {
            val magic = input.readInt()
            if (magic != MAGIC) throw IOException("bad magic")
            val hLen = input.readInt()
            if (hLen <= 0 || hLen > MAX_HEADER) throw IOException("bad header length $hLen")
            val h = ByteArray(hLen).also { input.readFully(it) }
            val bLen = input.readInt()
            if (bLen < 0 || bLen > MAX_BODY) throw IOException("bad body length $bLen")
            val body = ByteArray(bLen).also { input.readFully(it) }
            val o = JSONObject(String(h, Charsets.UTF_8))
            return Packet(
                id = o.getString("id"),
                kind = o.getString("k"),
                from = o.getString("f"),
                to = o.getString("t"),
                ttl = o.optInt("ttl", 1),
                ts = o.optLong("ts", 0),
                meta = o.optJSONObject("m") ?: JSONObject(),
                body = body,
            )
        }
    }
}

/** Encrypted payload = int jsonLen | json | binary attachment. */
object Payload {
    fun pack(json: JSONObject, binary: ByteArray? = null): ByteArray {
        val j = json.toString().toByteArray(Charsets.UTF_8)
        val bos = ByteArrayOutputStream(4 + j.size + (binary?.size ?: 0))
        DataOutputStream(bos).apply { writeInt(j.size); write(j); if (binary != null) write(binary); flush() }
        return bos.toByteArray()
    }

    fun unpack(bytes: ByteArray): Pair<JSONObject, ByteArray?> {
        val din = DataInputStream(bytes.inputStream())
        val len = din.readInt()
        if (len < 0 || len > bytes.size - 4) throw IOException("bad payload")
        val j = ByteArray(len).also { din.readFully(it) }
        val rest = bytes.size - 4 - len
        val bin = if (rest > 0) bytes.copyOfRange(4 + len, bytes.size) else null
        return JSONObject(String(j, Charsets.UTF_8)) to bin
    }
}
