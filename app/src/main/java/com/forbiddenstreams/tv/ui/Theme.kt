package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Forbidden Streams palette: black and slime green. */
object FS {
    val Bg = Color(0xFF040604)
    val BgGreen = Color(0xFF0A1F0B)
    val Panel = Color(0xFF0C140D)
    val Panel2 = Color(0xFF13211A)
    val Line = Color(0xFF1E3320)
    val Green = Color(0xFF7CFF3A)
    val GreenMid = Color(0xFF3FBF1F)
    val GreenDeep = Color(0xFF1E6B12)
    val GreenDim = Color(0xFF15361A)
    val Text = Color(0xFFE6F5DF)
    val TextDim = Color(0xFF8FA88A)
    val Live = Color(0xFFFF3B30)

    val background = Brush.linearGradient(listOf(Bg, Bg, BgGreen))
    val focusGradient = Brush.horizontalGradient(listOf(GreenDeep, GreenMid))
}

@Composable
fun ForbiddenTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = FS.Green,
            onPrimary = Color.Black,
            background = FS.Bg,
            onBackground = FS.Text,
            surface = FS.Panel,
            onSurface = FS.Text,
            surfaceVariant = FS.Panel2,
            onSurfaceVariant = FS.TextDim,
        ),
        content = content,
    )
}

/** A focusable card: dark green panel, glows green when focused. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    shape: Shape = RoundedCornerShape(10.dp),
    container: Color = FS.Panel,
    focusedScale: Float = 1.03f,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FS.GreenDim else container,
            contentColor = FS.Text,
            focusedContainerColor = FS.GreenDeep,
            focusedContentColor = Color.White,
            pressedContainerColor = FS.GreenMid,
            pressedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, FS.Green), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        content = content,
    )
}

/** Rounded pill used in the top menu and the date strip. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = false,
    fontSize: TextUnit = 17.sp,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected && !outlined) FS.Green else Color.Transparent,
            contentColor = if (selected && !outlined) Color.Black else FS.Text,
            focusedContainerColor = FS.Green,
            focusedContentColor = Color.Black,
            pressedContainerColor = FS.GreenMid,
            pressedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (selected && outlined) Border(BorderStroke(2.dp, FS.Green), shape = shape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(
            text,
            fontSize = fontSize,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/** Sidebar entry (Search, All, History, groups...). */
@Composable
fun SideItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) FS.Green else FS.Text,
            focusedContainerColor = FS.Green,
            focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Text(
            text,
            fontSize = 20.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, crop: Boolean = false) {
    if (url.isNullOrBlank()) return
    AsyncImage(
        model = url,
        contentDescription = null,
        modifier = modifier,
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
    )
}

@Composable
fun LoadingBox(text: String = "Loading") {
    var dots by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(400); dots = (dots + 1) % 4 } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text + ".".repeat(dots), color = FS.Green, fontSize = 22.sp)
    }
}

@Composable
fun MessageBox(title: String, body: String? = null, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = FS.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (body != null) Text(body, color = FS.TextDim, fontSize = 18.sp)
            if (action != null && onAction != null) Pill(action, selected = true, onClick = onAction)
        }
    }
}

@Composable
fun LiveDot(modifier: Modifier = Modifier) {
    Box(modifier.background(FS.Live, RoundedCornerShape(50)).padding(4.dp))
}

object Fmt {
    fun time(ms: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ms))
    fun range(a: Long, b: Long) = "${time(a)} – ${time(b)}"
    fun day(ms: Long): String = SimpleDateFormat("EEE, M/d", Locale.US).format(Date(ms)).uppercase(Locale.US)
    fun longDay(ms: Long): String = SimpleDateFormat("EEEE M/d", Locale.US).format(Date(ms))
}
