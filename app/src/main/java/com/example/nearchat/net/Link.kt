package com.example.nearchat.net

import android.util.Log
import com.example.nearchat.core.TransportKind
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * One live socket to a neighbouring phone (Bluetooth RFCOMM or Wi-Fi Direct TCP).
 * Has a dedicated reader thread and a writer thread fed by a queue, so a slow
 * transfer (e.g. an image over Bluetooth) never blocks the caller.
 */
class Link(
    val transport: TransportKind,
    val address: String,
    private val input: InputStream,
    private val output: OutputStream,
    private val resource: Closeable,
) {
    @Volatile var remoteId: String? = null
    @Volatile var remoteName: String? = null
    private val queue = LinkedBlockingQueue<ByteArray>(512)
    private val closedFlag = AtomicBoolean(false)
    val closed: Boolean get() = closedFlag.get()

    fun start(onPacket: (Link, Packet) -> Unit, onClosed: (Link) -> Unit) {
        thread(name = "link-reader-$address", isDaemon = true) {
            try {
                val din = DataInputStream(BufferedInputStream(input, 64 * 1024))
                while (!closed) {
                    val p = Packet.read(din)
                    try { onPacket(this, p) } catch (e: Exception) { Log.e(TAG, "packet handler failed", e) }
                }
            } catch (e: Exception) {
                if (!closed) Log.i(TAG, "link $address read ended: ${e.message}")
            } finally {
                close()
                onClosed(this)
            }
        }
        thread(name = "link-writer-$address", isDaemon = true) {
            try {
                val out = BufferedOutputStream(output, 64 * 1024)
                while (!closed) {
                    val frame = queue.poll(1, TimeUnit.SECONDS) ?: continue
                    out.write(frame)
                    if (queue.isEmpty()) out.flush()
                }
            } catch (e: Exception) {
                if (!closed) Log.i(TAG, "link $address write ended: ${e.message}")
            } finally {
                close()
            }
        }
    }

    fun send(p: Packet): Boolean {
        if (closed) return false
        return queue.offer(p.encode())
    }

    fun close() {
        if (!closedFlag.compareAndSet(false, true)) return
        queue.clear()
        try { resource.close() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "Link" }
}
