package com.forbiddenvid.tv

import android.app.Application
import com.forbiddenvid.tv.data.Addons
import com.forbiddenvid.tv.data.Watch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Addons.init(this)
        Watch.init(this)
    }
}
