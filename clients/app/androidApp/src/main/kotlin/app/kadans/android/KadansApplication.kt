package app.kadans.android

import android.app.Application
import app.kadans.di.initKoin

class KadansApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
    }
}
