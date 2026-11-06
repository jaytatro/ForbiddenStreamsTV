package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceGlow
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phones and tablets (Nov 6 2026).
 *
 * The TV library's buttons only react to the remote's OK button, never to a finger, so on a
 * phone nothing could be tapped. Every button in the app now goes through [Surface] below,
 * which adds tap and long-press on top of the TV button. With a remote nothing changes.
 *
 * "Touch" follows how the app is being used right now (Android's touch mode): touching the
 * screen turns it on, pressing a remote/keyboard key turns it off. TV boxes never touch it.
 */
@Composable
fun isTouch(): Boolean = LocalInputModeManager.current.inputMode == InputMode.Touch

/** Remote wording on TV, finger wording on a phone. */
@Composable
fun hint(remote: String, touch: String): String = if (isTouch()) touch else remote

/**
 * Drop-in for androidx.tv.material3.Surface (the clickable one): same look, plus
 * tap = onClick, long-press = onLongClick, with the pressed color while the finger is down.
 * In touch mode the button also stops taking the remote's highlight, so no stray green
 * outline sits on a random card while you're tapping around.
 */
@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    tonalElevation: Dp = 0.dp,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    glow: ClickableSurfaceGlow = ClickableSurfaceDefaults.glow(),
    content: @Composable (BoxScope.() -> Unit),
) {
    val touch = isTouch()
    val source = remember { MutableInteractionSource() }
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = if (touch && enabled) modifier.touchClick(source, onClick, onLongClick) else modifier,
        onLongClick = onLongClick,
        enabled = enabled,
        tonalElevation = tonalElevation,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        glow = glow,
        interactionSource = source,
        content = content,
    )
}

/** Tap / long-press for a finger, with pressed feedback sent to [source]. */
@Composable
private fun Modifier.touchClick(
    source: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier {
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val scope = rememberCoroutineScope()
    val hasLong = onLongClick != null
    return this
        .focusProperties { canFocus = false }
        .pointerInput(hasLong) {
            detectTapGestures(
                onPress = { offset ->
                    val press = PressInteraction.Press(offset)
                    // A short wait so a scroll that starts on a card doesn't flash it green.
                    val show = scope.launch { delay(60); source.emit(press) }
                    val tapped = tryAwaitRelease()
                    if (show.isActive) {
                        show.cancel()
                        if (tapped) { source.emit(press); delay(60); source.emit(PressInteraction.Release(press)) }
                    } else {
                        source.emit(if (tapped) PressInteraction.Release(press) else PressInteraction.Cancel(press))
                    }
                },
                onLongPress = if (hasLong) { _ -> long?.invoke() } else null,
                onTap = { click() },
            )
        }
}

/** Tap (and optional long-press) on something that isn't a button, only in touch mode. */
@Composable
fun Modifier.onTap(onLong: (() -> Unit)? = null, onTap: () -> Unit): Modifier {
    if (!isTouch()) return this
    val tap by rememberUpdatedState(onTap)
    val long by rememberUpdatedState(onLong)
    val hasLong = onLong != null
    return pointerInput(hasLong) {
        detectTapGestures(
            onLongPress = if (hasLong) { _ -> long?.invoke() } else null,
            onTap = { tap() },
        )
    }
}

/** Phones: full screen (status and navigation bars hidden; a swipe from the edge shows them for a moment). */
fun fullScreen(activity: android.app.Activity) {
    val w = activity.window
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
    androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).apply {
        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
