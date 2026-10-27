package com.forbiddenstreams.tv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.forbiddenstreams.tv.R
import com.forbiddenstreams.tv.data.LoginConfig
import com.forbiddenstreams.tv.data.Prefs
import com.forbiddenstreams.tv.data.Session
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    var mode by remember { mutableIntStateOf(0) } // 0 = Xtream, 1 = M3U
    var server by remember { mutableStateOf(prefs.lastField("server")) }
    var user by remember { mutableStateOf(prefs.lastField("user")) }
    var pass by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf(prefs.lastField("m3u")) }
    var epg by remember { mutableStateOf(prefs.lastField("epg")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (busy) return
        val config = if (mode == 0) {
            if (server.isBlank() || user.isBlank() || pass.isBlank()) { error = "Fill in server, username and password."; return }
            prefs.setLastField("server", server.trim()); prefs.setLastField("user", user.trim())
            LoginConfig.Xtream(server.trim(), user.trim(), pass.trim())
        } else {
            if (m3u.isBlank()) { error = "Enter your M3U playlist link."; return }
            prefs.setLastField("m3u", m3u.trim()); prefs.setLastField("epg", epg.trim())
            LoginConfig.M3u(m3u.trim(), epg.trim().ifEmpty { null })
        }
        busy = true
        error = null
        scope.launch {
            try {
                Session.login(context, config)
                onLoggedIn()
            } catch (e: Exception) {
                error = e.message ?: "Couldn't connect. Check the details and your internet."
            } finally {
                busy = false
            }
        }
    }

    Row(Modifier.fillMaxSize().padding(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.logo), contentDescription = "Forbidden Streams", modifier = Modifier.size(440.dp))
        }
        Column(
            Modifier.width(560.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sign in", color = FS.Green, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Xtream Codes", selected = mode == 0, onClick = { mode = 0; error = null })
                Pill("M3U Playlist", selected = mode == 1, onClick = { mode = 1; error = null })
            }
            if (mode == 0) {
                FsTextField("Server address (e.g. http://myserver.com:8080)", server, { server = it }, KeyboardType.Uri)
                FsTextField("Username", user, { user = it }, KeyboardType.Text)
                FsTextField("Password", pass, { pass = it }, KeyboardType.Password, password = true, done = { submit() })
            } else {
                FsTextField("M3U playlist link", m3u, { m3u = it }, KeyboardType.Uri)
                FsTextField("EPG (XMLTV) link — optional", epg, { epg = it }, KeyboardType.Uri, done = { submit() })
            }
            Spacer(Modifier.height(4.dp))
            Pill(if (busy) "Connecting…" else "Log in", selected = true, onClick = { submit() }, fontSize = 20.sp)
            error?.let { Text(it, color = FS.Live, fontSize = 17.sp) }
        }
    }
}

@Composable
fun FsTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType,
    password: Boolean = false,
    done: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = FS.TextDim, fontSize = 15.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = FS.Text, fontSize = 20.sp),
            cursorBrush = SolidColor(FS.Green),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboard,
                imeAction = if (done != null) ImeAction.Done else ImeAction.Next,
                autoCorrectEnabled = false,
            ),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { done?.invoke() }),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(if (focused) FS.GreenDim else FS.Panel, shape)
                .border(2.dp, if (focused) FS.Green else FS.Line, shape)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}
