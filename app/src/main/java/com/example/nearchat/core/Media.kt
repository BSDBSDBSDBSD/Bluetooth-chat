package com.example.nearchat.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File

object ImageUtil {
    private const val MAX_SIDE = 1600
    private const val MAX_BYTES = 1_200_000

    /** Decodes, downsizes and re-encodes as JPEG so it moves quickly over Bluetooth too. */
    fun prepare(context: Context, uri: Uri): ByteArray? = try {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        var quality = 85
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            out = bos.toByteArray()
            quality -= 15
        } while (out.size > MAX_BYTES && quality >= 40)
        bmp.recycle()
        out
    } catch (e: Exception) {
        Log.e("ImageUtil", "prepare failed", e); null
    }

    /** Center-crops to a square profile picture, small enough to share with every contact. */
    fun prepareAvatar(context: Context, uri: Uri): ByteArray? = try {
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        val full = ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val w = info.size.width; val h = info.size.height
            val scale = minOf(1f, 720f / minOf(w, h))
            if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val side = minOf(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, AVATAR_SIDE, AVATAR_SIDE, true)
        var quality = 85
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
            out = bos.toByteArray()
            quality -= 10
        } while (out.size > 60_000 && quality >= 40)
        out
    } catch (e: Exception) {
        Log.e("ImageUtil", "prepareAvatar failed", e); null
    }

    private const val AVATAR_SIDE = 320
}

/** Records AAC voice notes into a temporary file. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording get() = recorder != null

    fun start(): Boolean {
        cancel()
        val f = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        return try {
            val r = MediaRecorder(context)
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22050)
            r.setAudioEncodingBitRate(32000)
            r.setMaxDuration(MAX_MS)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            recorder = r; file = f; startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            Log.e("VoiceRecorder", "start failed", e); f.delete(); false
        }
    }

    fun elapsedMs(): Long = if (recorder == null) 0 else SystemClock.elapsedRealtime() - startedAt

    /** Returns audio bytes and duration, or null if the recording was too short / failed. */
    fun stop(): Pair<ByteArray, Long>? {
        val r = recorder ?: return null
        val f = file
        val dur = elapsedMs()
        recorder = null; file = null
        return try {
            r.stop(); r.release()
            if (f == null || dur < 700) null else f.readBytes() to dur
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            null
        } finally { f?.delete() }
    }

    fun cancel() {
        val r = recorder ?: return
        try { r.stop() } catch (_: Exception) {}
        try { r.release() } catch (_: Exception) {}
        recorder = null
        file?.delete(); file = null
    }

    companion object { const val MAX_MS = 5 * 60 * 1000 }
}

/** Plays decrypted voice notes; only one at a time. */
class VoicePlayer(private val context: Context, private val loader: (String) -> ByteArray?, private val onChange: () -> Unit) {
    private var player: MediaPlayer? = null
    @Volatile var playingId: String? = null; private set

    fun toggle(id: String) { if (playingId == id) stop() else play(id) }

    fun play(id: String) {
        stop()
        val bytes = loader(id) ?: return
        val f = File(context.cacheDir, "play.m4a")
        try {
            f.writeBytes(bytes)
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p; playingId = id
        } catch (e: Exception) {
            Log.e("VoicePlayer", "play failed", e)
        }
        onChange()
    }

    fun positionMs(): Int = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }

    fun stop() {
        player?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        player = null
        playingId = null
        File(context.cacheDir, "play.m4a").delete()
        onChange()
    }
}
