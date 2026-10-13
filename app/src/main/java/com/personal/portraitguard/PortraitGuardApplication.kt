package com.personal.portraitguard

import android.app.Application

class PortraitGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfigStore.initialize(this)
    }
}

