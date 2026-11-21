package com.forbiddenvid.tv.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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

/** Same palette as Forbidden Streams TV: black and slime green. */
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
    val Red = Color(0xFFFF3B30)

    val background = Brush.linearGradient(listOf(Bg, Bg, BgGreen))
}

@Composable
fun ForbiddenTheme(content: @Composable () -> Unit) {
    // Everything drawn ~15% smaller than the default TV size (like Forbidden Streams TV).
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * 0.85f, d.fontScale)) {
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

/** Rounded green pill button. */
@Composable
fun Pill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 17.sp,
    hPad: Dp = 18.dp,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) FS.Green else FS.Panel2,
            contentColor = if (selected) Color.Black else FS.Text,
            focusedContainerColor = FS.Green,
            focusedContentColor = Color.Black,
            pressedContainerColor = FS.GreenMid,
            pressedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = hPad, vertical = 8.dp))
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
fun LoadingBox(text: String = "Loading", modifier: Modifier = Modifier.fillMaxSize()) {
    var dots by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(400); dots = (dots + 1) % 4 } }
    Box(modifier, contentAlignment = Alignment.Center) {
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

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

/** Single-line (outline) icons for the side menu, drawn on a 24×24 grid. */
enum class Icon(val d: String) {
    HOME("M3 11l9 -7l9 7v9a1 1 0 0 1 -1 1h-5v-6h-6v6h-5a1 1 0 0 1 -1 -1z"),
    MOVIES("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z M7 3v18 M17 3v18 M3 8h4 M3 12h4 M3 16h4 M17 8h4 M17 12h4 M17 16h4"),
    SHOWS("M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z M10 8.5v5l4.5 -2.5z M7 21h10"),
    SEARCH("M10.5 3a7.5 7.5 0 1 1 0 15a7.5 7.5 0 1 1 0 -15z M16 16l5 5"),
    ADDONS("M4 4h6v6h-6z M14 4h6v6h-6z M4 14h6v6h-6z M17 14v6 M14 17h6"),
}

@Composable
fun LineIcon(icon: Icon, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Thin green progress line (continue watching). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0x99000000))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(FS.Green))
    }
}
