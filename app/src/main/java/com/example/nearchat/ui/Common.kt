package com.example.nearchat.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearchat.core.ChatCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Bumped every time the core reports a change; screens re-read data keyed on it. */
val LocalRev = compositionLocalOf { 0 }

private val avatarColors = listOf(
    Color(0xFF405DE6), Color(0xFF00897B), Color(0xFF8E24AA), Color(0xFFD81B60),
    Color(0xFFF4511E), Color(0xFF3949AB), Color(0xFF43A047), Color(0xFF6D4C41),
)

@Composable
fun Avatar(name: String, seed: String, group: Boolean = false, size: Dp = 44.dp) {
    val color = avatarColors[(seed.hashCode() and 0x7fffffff) % avatarColors.size]
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        if (group) Icon(Icons.Default.Group, contentDescription = null, tint = Color.White)
        else Text(name.trim().take(1).ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

@Composable
fun OnlineDot(online: Boolean) {
    Box(Modifier.size(10.dp).clip(CircleShape).background(if (online) Color(0xFF2E7D32) else Color(0xFF9E9E9E)))
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("dd/MM", Locale.getDefault())

fun formatTime(ts: Long): String = timeFmt.format(Date(ts))

fun formatListTime(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (sameDay) timeFmt.format(Date(ts)) else dateFmt.format(Date(ts))
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

object ImageCache {
    private val cache = LruCache<String, ImageBitmap>(40)
    fun get(id: String): ImageBitmap? = cache.get(id)
    fun load(core: ChatCore, id: String): ImageBitmap? {
        cache.get(id)?.let { return it }
        val bytes = core.loadMedia(id) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return null
        cache.put(id, bmp)
        return bmp
    }
}

/** Shows an image stored encrypted on disk; decrypts and decodes off the main thread. */
@Composable
fun EncryptedImage(core: ChatCore, id: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Fit) {
    val state by produceState<Pair<Boolean, ImageBitmap?>>(initialValue = (ImageCache.get(id) != null) to ImageCache.get(id), id) {
        if (value.second == null) value = true to withContext(Dispatchers.IO) { ImageCache.load(core, id) }
    }
    val bmp = state.second
    when {
        bmp != null -> Image(bitmap = bmp, contentDescription = "תמונה", modifier = modifier, contentScale = contentScale)
        !state.first -> Box(modifier.size(180.dp, 140.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else -> Box(modifier.size(180.dp, 140.dp), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.BrokenImage, contentDescription = "התמונה לא זמינה", tint = MaterialTheme.colorScheme.outline)
        }
    }
}
