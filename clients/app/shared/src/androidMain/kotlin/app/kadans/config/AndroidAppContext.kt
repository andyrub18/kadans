package app.kadans.config

import android.content.Context

/**
 * The application's context, for what the shared code asks of Android away from any screen: alarms, notifications,
 * background jobs. Attached first thing in `Application.onCreate`, which runs before any receiver, job or service.
 */
object AndroidAppContext {
    lateinit var context: Context
        private set

    fun attach(application: Context) {
        context = application.applicationContext
    }
}
