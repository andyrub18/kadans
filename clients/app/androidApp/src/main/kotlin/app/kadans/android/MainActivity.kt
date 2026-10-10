package app.kadans.android

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import app.kadans.auth.AndroidActivityHolder
import app.kadans.ui.App
import app.kadans.ui.IncomingLinks
import app.kadans.ui.brand.KadansMark
import app.kadans.ui.brand.SplashOver
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
        setContent {
            // Before Android 12 the window shows the still K while the app starts (splash_background): the app keeps it
            // as long as Android 12+ keeps the settled K, then fades the same way. Same size and place: no jump.
            SplashOver(show = Build.VERSION.SDK_INT < Build.VERSION_CODES.S, wind = false, markSize = 128.dp) {
                App(deepLink = deepLink)
            }
        }
    }

    /** A reminder tapped while the app is open: its todo opens on top of where the person is. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        linkOf(intent)?.let(IncomingLinks::open)
    }

    /**
     * Android 12+ removes its splash as soon as the app draws, which can cut the hands short of the K: the splash
     * stays until its one-second animation has played and the K has been held as on the desktop
     * ([KadansMark.HOLD_MS]), then fades.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun letTheSplashFinish() {
        splashScreen.setOnExitAnimationListener { view ->
            val start = view.iconAnimationStart?.toEpochMilli()
            // As long as the system plays it and holds the K: slowed down with the animations, not at all when they are
            // off (accessibility).
            val animation = view.iconAnimationDuration?.toMillis() ?: 0
            val shown = ((animation + KadansMark.HOLD_MS) * ValueAnimator.getDurationScale()).toLong()
            // The start is wall-clock time (the system takes Instant.now()), so it is measured against that clock;
            // anything odd waits at most that long.
            val left = if (start == null) 0 else (start + shown - System.currentTimeMillis()).coerceIn(0, shown)
            view.postDelayed({
                view.animate().alpha(0f).setDuration(KadansMark.FADE_MS.toLong()).withEndAction(view::remove).start()
            }, left)
        }
    }

    override fun onDestroy() {
        AndroidActivityHolder.detach(this)
        super.onDestroy()
    }
}
