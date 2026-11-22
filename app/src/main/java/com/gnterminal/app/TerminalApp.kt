package com.gnterminal.app

import android.app.Application
import com.gnterminal.app.widget.RefreshWorker

class TerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RefreshWorker.schedule(this)
    }
}
