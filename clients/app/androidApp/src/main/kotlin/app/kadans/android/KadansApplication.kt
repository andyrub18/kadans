package app.kadans.android

import android.app.Application
import app.kadans.config.AndroidAppContext
import app.kadans.di.initKoin
import app.kadans.notifications.KadansPushChannel

class KadansApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidAppContext.attach(this) // before anything asks Android for alarms or notifications
        initKoin()
        KadansPushChannel.ensure(this)
    }
}
