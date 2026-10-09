package app.kadans.android

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.kadans.auth.AndroidActivityHolder
import app.kadans.ui.App
import app.kadans.ui.IncomingLinks
import app.kadans.ui.linkOf

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Kadans) // the starting theme (the splash) has done its part
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) letTheSplashFinish()
        AndroidActivityHolder.attach(this) // Credential Manager (Google sign-in) needs an Activity

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val deepLink = linkOf(intent)
        setContent { App(deepLink = deepLink) }
    }

    /** A reminder tapped while the app is open: its todo opens on top of where the person is. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkOf(intent)?.let(IncomingLinks::open)
    }

    /**
     * Android 12+ removes its splash as soon as the app draws, which can cut the hands short of the K: the splash
     * stays until its one-second animation has played, then fades.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun letTheSplashFinish() {
        splashScreen.setOnExitAnimationListener { view ->
            val start = view.iconAnimationStart?.toEpochMilli()
            // As long as the system plays it: slowed down, or not at all when animations are off (accessibility).
            val length = ((view.iconAnimationDuration?.toMillis() ?: 0) * ValueAnimator.getDurationScale()).toLong()
            // The start is on the uptime clock; anything odd waits at most the animation's length.
            val left = if (start == null) 0 else (start + length - SystemClock.uptimeMillis()).coerceIn(0, length)
            view.postDelayed({ view.animate().alpha(0f).setDuration(200).withEndAction(view::remove).start() }, left)
        }
    }

    override fun onDestroy() {
        AndroidActivityHolder.detach(this)
        super.onDestroy()
    }
}
