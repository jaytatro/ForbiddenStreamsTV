package com.forbiddenstreams.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.delay

/** True while the opening loading screen is up (AppShell waits with its focus until it's gone). */
object LoadScreen {
    var showing by mutableStateOf(true)
}

/**
 * Nov 16: a loading screen when the app is opened — the logo, the name and a green bar — while the app
 * gets ready behind it (saved channel list, playlist refresh, Home). It fades out once the channels are
 * there (at least 1.2 s, at most 6 s), so the first screen you see is ready to use.
 */
@Composable
fun LoadScreenHost() {
    LaunchedEffect(Unit) {
        val t0 = System.currentTimeMillis()
        while (true) {
            val e = System.currentTimeMillis() - t0
            if ((Session.liveAll != null && e >= 1_200) || e >= 6_000) break
            delay(100)
        }
        LoadScreen.showing = false
    }
    AnimatedVisibility(visible = LoadScreen.showing, exit = fadeOut(tween(400))) {
        val pulse = rememberInfiniteTransition(label = "load")
        val bar = pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100), RepeatMode.Restart), label = "bar")
        val glow = pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "glow")
        Box(
            Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF0B2412), Color.Black), radius = 1400f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(220.dp).alpha(glow.value))
                Spacer(Modifier.height(18.dp))
                Text("FORBIDDEN STREAMS TV", color = FS.Green, fontSize = 26.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Spacer(Modifier.height(22.dp))
                // A green bar sweeping across.
                Box(Modifier.width(320.dp).height(6.dp).background(Color(0x22FFFFFF), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(0.25f + 0.75f * bar.value).fillMaxHeight().background(FS.Green, RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(12.dp))
                Text(if (Session.liveAll == null) "Loading your channels…" else "Getting everything ready…",
                    color = FS.TextDim, fontSize = 15.sp)
            }
        }
    }
}
