package com.forbiddenvid.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.forbiddenvid.tv.ui.AppShell
import com.forbiddenvid.tv.ui.ForbiddenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForbiddenTheme { AppShell() } }
    }
}
