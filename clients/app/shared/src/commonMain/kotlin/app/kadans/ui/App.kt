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
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import app.kadans.ui.auth.LoginScreen
import app.kadans.ui.auth.MfaScreen
import app.kadans.ui.auth.RegisterScreen
import app.kadans.ui.calendar.CalendarScreen
import app.kadans.ui.home.HomeScreen
import app.kadans.ui.settings.SettingsScreen
import app.kadans.ui.todos.CreateTodoScreen
import app.kadans.ui.todos.EditTodoScreen
import app.kadans.ui.todos.TodoDetailScreen
import app.kadans.ui.pomodoro.PomodoroScreen
import app.kadans.ui.templates.TemplatesScreen
import org.koin.compose.koinInject

// Navigation 3: routes are plain keys; the back stack is state we own.
data object LoginRoute
data object RegisterRoute
data class MfaRoute(val mfaToken: String)
data object HomeRoute
data object CreateTodoRoute
data object TemplatesRoute
data object CalendarRoute
data object SettingsRoute
data class TodoDetailRoute(val todoId: String)
data class EditTodoRoute(val todoId: String)
data class PomodoroRoute(val todoId: String, val loop: Boolean = true, val handsFree: Boolean = false)

@Composable
fun App() {
    KadansTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val languageController = koinInject<LanguageController>()
            val language by languageController.language.collectAsState()
            val tokenStore = koinInject<TokenStore>()
            var hasSession by remember { mutableStateOf<Boolean?>(null) }
            LaunchedEffect(Unit) { hasSession = tokenStore.load() != null }

            when (hasSession) {
                null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> CompositionLocalProvider(LocalStrings provides language.catalog) {
                    KadansNav(startAtHome = hasSession == true, languageController = languageController)
                }
            }
        }
    }
}

@Composable
private fun KadansNav(startAtHome: Boolean, languageController: LanguageController) {
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
                        languageController = languageController,
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
                    HomeScreen(
                        onLoggedOut = { resetTo(LoginRoute) },
                        languageController = languageController,
                        onCreateTodo = { backStack.add(CreateTodoRoute) },
                        onOpenTemplates = { backStack.add(TemplatesRoute) },
                        onOpenCalendar = { backStack.add(CalendarRoute) },
                        onOpenSettings = { backStack.add(SettingsRoute) },
                        onOpenTodo = { todoId -> backStack.add(TodoDetailRoute(todoId)) },
                    )
                }
                is TemplatesRoute -> NavEntry(key) {
                    TemplatesScreen(onBack = { backStack.removeLastOrNull() })
                }
                is CalendarRoute -> NavEntry(key) {
                    CalendarScreen(
                        onOpenTodo = { todoId -> backStack.add(TodoDetailRoute(todoId)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is SettingsRoute -> NavEntry(key) {
                    SettingsScreen(
                        languageController = languageController,
                        onLoggedOut = { resetTo(LoginRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is CreateTodoRoute -> NavEntry(key) {
                    CreateTodoScreen(
                        onCreated = { todoId -> backStack.removeLastOrNull(); backStack.add(TodoDetailRoute(todoId)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is TodoDetailRoute -> NavEntry(key) {
                    TodoDetailScreen(
                        todoId = key.todoId,
                        onOpenPomodoro = { loop, handsFree -> backStack.add(PomodoroRoute(key.todoId, loop, handsFree)) },
                        onEdit = { backStack.add(EditTodoRoute(key.todoId)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is EditTodoRoute -> NavEntry(key) {
                    EditTodoScreen(
                        todoId = key.todoId,
                        onSaved = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is PomodoroRoute -> NavEntry(key) {
                    PomodoroScreen(
                        todoId = key.todoId,
                        loop = key.loop,
                        handsFree = key.handsFree,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                else -> error("Unknown route: $key")
            }
        },
    )
}
