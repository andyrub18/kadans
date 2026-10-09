package app.kadans.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
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

    override fun onDestroy() {
        AndroidActivityHolder.detach(this)
        super.onDestroy()
    }
}
