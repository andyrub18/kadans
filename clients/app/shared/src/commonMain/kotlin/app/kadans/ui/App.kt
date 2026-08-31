package app.kadans.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import app.kadans.api.TokenStore
import app.kadans.ui.auth.LoginScreen
import app.kadans.ui.auth.MfaScreen
import app.kadans.ui.auth.RegisterScreen
import app.kadans.ui.home.HomeScreen
import org.koin.compose.koinInject

// Navigation 3: routes are plain keys; the back stack is state we own.
data object LoginRoute
data object RegisterRoute
data class MfaRoute(val mfaToken: String)
data object HomeRoute

@Composable
fun App() {
    KadansTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val tokenStore = koinInject<TokenStore>()
            var hasSession by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) { hasSession = tokenStore.load() != null }

            when (hasSession) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> KadansNav(startAtHome = hasSession == true)
            }
        }
    }
}

@Composable
private fun KadansNav(startAtHome: Boolean) {
    val backStack = remember {
        mutableStateListOf<Any>(if (startAtHome) HomeRoute else LoginRoute)
    }

    fun resetTo(route: Any) {
        backStack.clear()
        backStack.add(route)
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is LoginRoute -> NavEntry(key) {
                    LoginScreen(
                        onLoggedIn = { resetTo(HomeRoute) },
                        onMfaRequired = { mfaToken -> backStack.add(MfaRoute(mfaToken)) },
                        onRegister = { backStack.add(RegisterRoute) },
                    )
                }
                is MfaRoute -> NavEntry(key) {
                    MfaScreen(
                        mfaToken = key.mfaToken,
                        onVerified = { resetTo(HomeRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is RegisterRoute -> NavEntry(key) {
                    RegisterScreen(
                        onRegistered = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is HomeRoute -> NavEntry(key) {
                    HomeScreen(onLoggedOut = { resetTo(LoginRoute) })
                }
                else -> error("Unknown route: $key")
            }
        },
    )
}
