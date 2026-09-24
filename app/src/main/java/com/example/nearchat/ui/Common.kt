package com.example.nearchat.ui

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nearchat.core.ChatCore
import com.example.nearchat.core.avatarMediaId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Bumped every time the core reports a change; screens re-read data keyed on it. */
val LocalRev = compositionLocalOf { 0 }
val LocalCore = staticCompositionLocalOf<ChatCore> { error("no core") }

private val avatarGradients = listOf(
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6)),
    listOf(Color(0xFF06B6D4), Color(0xFF3B82F6)),
    listOf(Color(0xFFF59E0B), Color(0xFFEF4444)),
    listOf(Color(0xFF10B981), Color(0xFF059669)),
    listOf(Color(0xFFEC4899), Color(0xFFF43F5E)),
    listOf(Color(0xFF8B5CF6), Color(0xFFD946EF)),
    listOf(Color(0xFF14B8A6), Color(0xFF6366F1)),
    listOf(Color(0xFFF97316), Color(0xFFDB2777)),
)

/**
 * Round avatar: the person's profile picture if we have it, otherwise their initial
 * on a colourful gradient. [online] draws a presence dot (null = no dot).
 */
@Composable
fun Avatar(
    name: String,
    seed: String,
    size: Dp = 48.dp,
    group: Boolean = false,
    avatarHash: String = "",
    online: Boolean? = null,
    ring: Boolean = false,
) {
    val core = LocalCore.current
    val bmp by produceState(initialValue = if (avatarHash.isEmpty()) null else AvatarCache.get(seed, avatarHash), seed, avatarHash) {
        // produceState keeps its value across key changes, so always resolve the current picture.
        value = if (avatarHash.isEmpty()) null
        else AvatarCache.get(seed, avatarHash) ?: withContext(Dispatchers.IO) { AvatarCache.load(core, seed, avatarHash) }
    }
    Box(Modifier.size(size)) {
        val ringMod = if (ring) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier
        Box(
            Modifier.size(size).clip(CircleShape)
                .background(Brush.linearGradient(avatarGradients[(seed.hashCode() and 0x7fffffff) % avatarGradients.size]))
                .then(ringMod),
            contentAlignment = Alignment.Center,
        ) {
            val b = bmp
            when {
                b != null -> Image(b, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape).then(ringMod))
                group -> Icon(Icons.Default.Groups, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
                else -> Text(
                    name.trim().take(1).ifEmpty { "?" }, color = Color.White,
                    fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp,
                )
            }
        }
        if (online != null) {
            val dot = (size.value * 0.28f).coerceIn(10f, 18f).dp
            Box(
                Modifier.align(Alignment.BottomStart).size(dot).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface).padding(2.dp).clip(CircleShape)
                    .background(if (online) Brand.Online else MaterialTheme.colorScheme.outline),
            )
        }
    }
}

object AvatarCache {
    private val cache = LruCache<String, ImageBitmap>(60)
    fun get(userId: String, hash: String): ImageBitmap? = cache.get("$userId#$hash")
    fun load(core: ChatCore, userId: String, hash: String): ImageBitmap? {
        get(userId, hash)?.let { return it }
        val bytes = core.loadMedia(avatarMediaId(userId)) ?: return null
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return null
        cache.put("$userId#$hash", bmp)
        return bmp
    }
}

/** Gradient header used at the top of every screen; extends under the status bar. */
@Composable
fun GradientHeader(
    modifier: Modifier = Modifier,
    rounded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = if (rounded) RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp) else RoundedCornerShape(0.dp)
    Column(
        modifier.fillMaxWidth().shadow(10.dp, shape, ambientColor = Brand.Violet, spotColor = Brand.Violet)
            .clip(shape).background(Brand.header).statusBarsPadding(),
        content = content,
    )
}

/** Compact top bar for inner screens: back arrow, title and optional actions, on the brand gradient. */
@Composable
fun GradientTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    GradientHeader(rounded = false) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה", tint = Color.White) }
            Row(
                Modifier.weight(1f).then(if (onTitleClick != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onTitleClick) else Modifier)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leading != null) { leading(); Spacer(Modifier.width(10.dp)) }
                Column {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            actions()
        }
    }
}

@Composable
fun HeaderIcon(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Pill-shaped button filled with the brand gradient. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(if (enabled) Brand.bubble else Brush.linearGradient(listOf(Color(0xFFB8B8C8), Color(0xFFB8B8C8))))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = Color.White); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

/** Round gradient action button (FAB, send, mic). */
@Composable
fun GradientCircleButton(icon: ImageVector, description: String, onClick: () -> Unit, size: Dp = 56.dp, brush: Brush = Brand.bubble) {
    Box(
        Modifier.size(size).shadow(8.dp, CircleShape, spotColor = Brand.Violet).clip(CircleShape).background(brush).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(size * 0.45f)) }
}

/** Rounded surface card used for grouped settings/sections. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
        content = content,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(96.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Brand.Indigo.copy(alpha = 0.18f), Brand.Pink.copy(alpha = 0.18f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Brand.Violet, modifier = Modifier.size(44.dp)) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) { Spacer(Modifier.height(20.dp)); action() }
    }
}

@Composable
fun OnlineDot(online: Boolean) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(if (online) Brand.Online else MaterialTheme.colorScheme.outline))
}

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dateFmt = SimpleDateFormat("dd/MM", Locale.getDefault())
private val longDateFmt = SimpleDateFormat("d.M.yyyy", Locale.getDefault())

fun formatTime(ts: Long): String = timeFmt.format(Date(ts))

private fun dayIndex(ts: Long): Long {
    val c = Calendar.getInstance().apply { timeInMillis = ts }
    return c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR)
}

fun sameDay(a: Long, b: Long) = dayIndex(a) == dayIndex(b)

fun formatListTime(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> timeFmt.format(Date(ts))
        sameDay(ts, now - 86_400_000L) -> "אתמול"
        else -> dateFmt.format(Date(ts))
    }
}

/** Label for the date chips between messages. */
fun formatDayLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    return when {
        sameDay(ts, now) -> "היום"
        sameDay(ts, now - 86_400_000L) -> "אתמול"
        else -> longDateFmt.format(Date(ts))
    }
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
