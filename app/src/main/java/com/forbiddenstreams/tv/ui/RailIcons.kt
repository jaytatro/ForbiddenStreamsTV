package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp

/** Single-line (outline) icons for the left rail, drawn on a 24×24 grid. */
enum class RailIcon(val d: String) {
    CHANNELS("M5 6h14a2 2 0 0 1 2 2v8a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-8a2 2 0 0 1 2 -2z M8 21h8 M9 2l3 4l3 -4"),
    MOVIES("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1 -2 2h-14a2 2 0 0 1 -2 -2v-14a2 2 0 0 1 2 -2z M7 3v18 M17 3v18 M3 8h4 M3 12h4 M3 16h4 M17 8h4 M17 12h4 M17 16h4"),
    SHOWS("M4 4h16a2 2 0 0 1 2 2v10a2 2 0 0 1 -2 2h-16a2 2 0 0 1 -2 -2v-10a2 2 0 0 1 2 -2z M10 8.5v5l4.5 -2.5z M7 21h10"),
    SPLIT("M4 4h6v6h-6z M14 4h6v6h-6z M4 14h6v6h-6z M14 14h6v6h-6z"),
    RECORDINGS("M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0 -18z M12 8.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0 -7z"),
    SPORTS("M7 4h10v5a5 5 0 0 1 -10 0z M7 6h-3a3 3 0 0 0 3 4 M17 6h3a3 3 0 0 1 -3 4 M12 14v4 M8 21h8 M10 18h4"),
    SETTINGS(
        "M15 12a3 3 0 1 1 -6 0a3 3 0 1 1 6 0z " +
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1 .1a2 2 0 1 1 -2.8 2.8l-.1 -.1a1.7 1.7 0 0 0 -1.8 -.3a1.7 1.7 0 0 0 -1 1.5v.2a2 2 0 1 1 -4 0v-.1a1.7 1.7 0 0 0 -1.1 -1.5a1.7 1.7 0 0 0 -1.8 .3l-.1 .1a2 2 0 1 1 -2.8 -2.8l.1 -.1a1.7 1.7 0 0 0 .3 -1.8a1.7 1.7 0 0 0 -1.5 -1h-.2a2 2 0 1 1 0 -4h.1a1.7 1.7 0 0 0 1.5 -1.1a1.7 1.7 0 0 0 -.3 -1.8l-.1 -.1a2 2 0 1 1 2.8 -2.8l.1 .1a1.7 1.7 0 0 0 1.8 .3h.1a1.7 1.7 0 0 0 1 -1.5v-.2a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5a1.7 1.7 0 0 0 1.8 -.3l.1 -.1a2 2 0 1 1 2.8 2.8l-.1 .1a1.7 1.7 0 0 0 -.3 1.8v.1a1.7 1.7 0 0 0 1.5 1h.2a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0 -1.5 1z",
    ),
}

@Composable
fun LineIcon(icon: RailIcon, size: Dp, color: Color, modifier: Modifier = Modifier) {
    val path = remember(icon) { PathParser().parsePathString(icon.d).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            drawPath(path, color, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
