package app.kadans.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.kadans.api.TokenStore
import app.kadans.ui.auth.LoginScreen
import app.kadans.ui.auth.MfaScreen
import app.kadans.ui.auth.RegisterScreen
import app.kadans.ui.home.HomeScreen
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable object LoginRoute
@Serializable object RegisterRoute
@Serializable data class MfaRoute(val mfaToken: String)
@Serializable object HomeRoute

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val tokenStore = koinInject<TokenStore>()
            var hasSession by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) { hasSession = tokenStore.load() != null }

            when (hasSession) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> KadansNavHost(startAtHome = hasSession == true)
            }
        }
    }
}

@Composable
private fun KadansNavHost(startAtHome: Boolean) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = if (startAtHome) HomeRoute else LoginRoute,
    ) {
        composable<LoginRoute> {
            LoginScreen(
                onLoggedIn = {
                    navController.navigate(HomeRoute) { popUpTo(LoginRoute) { inclusive = true } }
                },
                onMfaRequired = { mfaToken -> navController.navigate(MfaRoute(mfaToken)) },
                onRegister = { navController.navigate(RegisterRoute) },
            )
        }
        composable<MfaRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<MfaRoute>()
            MfaScreen(
                mfaToken = route.mfaToken,
                onVerified = {
                    navController.navigate(HomeRoute) { popUpTo(LoginRoute) { inclusive = true } }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable<RegisterRoute> {
            RegisterScreen(
                onRegistered = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
            )
        }
        composable<HomeRoute> {
            HomeScreen(
                onLoggedOut = {
                    navController.navigate(LoginRoute) { popUpTo(HomeRoute) { inclusive = true } }
                },
            )
        }
    }
}
