package app.kadans.android

import android.app.Application
import app.kadans.di.initKoin
import app.kadans.notifications.KadansPushChannel

class KadansApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin()
        KadansPushChannel.ensure(this)
    }
}
