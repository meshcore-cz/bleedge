package cz.meshcore.sidepath.debug

import android.app.Application
import android.util.Log

class SidepathDebugApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.i("SidepathDebugApp", "Application started")
    }
}
