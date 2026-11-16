package app.kadans.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.kadans.api.KadansApi
import app.kadans.api.TokenStore
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import app.kadans.realtime.KadansRealtime
import app.kadans.reminders.LocalReminders
import app.kadans.sync.DataChanges
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import app.kadans.ui.auth.ForgotPasswordScreen
import app.kadans.ui.auth.LoginScreen
import app.kadans.ui.auth.MfaScreen
import app.kadans.ui.auth.RegisterScreen
import app.kadans.ui.auth.ResetPasswordScreen
import app.kadans.ui.budget.BudgetAddScreen
import app.kadans.ui.budget.BudgetScreen
import app.kadans.ui.calendar.CalendarScreen
import app.kadans.ui.home.HomeScreen
import app.kadans.ui.notifications.NotificationsScreen
import app.kadans.ui.focus.FocusScreen
import app.kadans.ui.settings.SettingsPage
import app.kadans.ui.settings.SettingsScreen
import app.kadans.ui.shell.AddAction
import app.kadans.ui.shell.MainShell
import app.kadans.ui.shell.MainTab
import app.kadans.share.TodoDraft
import app.kadans.ui.todos.CreateTodoScreen
import app.kadans.ui.todos.SharedTodoScreen
import app.kadans.ui.todos.EditTodoScreen
import app.kadans.ui.todos.TodoDetailScreen
import app.kadans.ui.pomodoro.PomodoroScreen
import app.kadans.ui.auth.KeepAccountScreen
import app.kadans.ui.billing.PaywallScreen
import org.koin.compose.koinInject
import kotlinx.datetime.toLocalDateTime

// Navigation 3: routes are plain keys; the back stack is state we own.
data object LoginRoute
data object RegisterRoute
data object ForgotPasswordRoute
data class ResetPasswordRoute(val email: String, val token: String)
data class MfaRoute(val mfaToken: String)

/** A sign-in into an account awaiting erasure: keep it, or leave it closed. */
data class KeepAccountRoute(val eraseAfterMillis: Long, val restoreToken: String)
data object HomeRoute

/** A phone whose account has no subscription (once they are required): subscribe, restore, or sign out. */
data object PaywallRoute
data object CreateTodoRoute

/**
 * The form for a new todo, filled in from a text another app shared (ARCHITECTURE → "Share to Kadans"). Opened by that
 * share it is alone on the screen: saved or left, the person is back in the app they shared from.
 */
data class SharedTodoRoute(val title: String, val description: String)
data object CalendarRoute
/** The focus stats and the Pomodoro cycles (FocusScreen). */
data object FocusRoute
data object SettingsRoute
/** One of Settings' pages, opened from a row of the main one. */
data class SettingsPageRoute(val page: SettingsPage)
data object NotificationsRoute
data object BudgetRoute
data object BudgetAddRoute
/** A movement of the budget opened to correct it. */
data class BudgetEditRoute(val transactionId: String)
data class TodoDetailRoute(val todoId: String)
data class EditTodoRoute(val todoId: String)
/** [finishAtMillis]: when a new session should end by itself (epoch ms); null leaves the default, 12 hours. */
/** [startsOne]: false for a link, which shows the session running (or the last one) and never starts another. */
data class PomodoroRoute(
    val todoId: String,
    val loop: Boolean = true,
    val handsFree: Boolean = false,
    val finishAtMillis: Long? = null,
    val startsOne: Boolean = true,
)

/**
 * [deepLink]: what the app was opened for (a reminder's todo, the widget's "+", a text another app shared).
 * [leave]: closes this window of the app and hands the person back to where they came from, with a word to show them
 * there if there is one (Android: the activity another app's share opened; nowhere else is there such a thing).
 */
@Composable
fun App(deepLink: String? = null, leave: ((message: String?) -> Unit)? = null) {
    KadansTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            // Edge to edge on Android: the background goes under the system bars, the content stays clear of them and
            // of the keyboard. Consumed here, so a Scaffold further down does not pad twice.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val languageController = koinInject<LanguageController>()
                val language by languageController.language.collectAsState()
                val clock by koinInject<app.kadans.profile.ClockPreference>().format.collectAsState()
                val dates = remember(language, clock) { Dates(language.catalog.dates, clock) }
                val tokenStore = koinInject<TokenStore>()
                var hasSession by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) { hasSession = tokenStore.load() != null }
                // Back in the foreground: the reminders this device rings are fetched again, unless they just were, and
                // the screens load what they show again (it may have changed elsewhere meanwhile). Not at the first
                // start: the screens load then anyway.
                val localReminders = koinInject<LocalReminders>()
                val dataChanges = koinInject<DataChanges>()
                // The home-screen widget too (Android): it may have been placed, or gone stale, while the app was away.
                val todayWidget = koinInject<app.kadans.widget.TodayWidget>()
                // Do Not Disturb during focus (Android): the person may be back from the system's settings with the
                // access given, and a session may have changed while the app was away.
                val focusQuiet = koinInject<app.kadans.quiet.FocusQuiet>()
                var startedBefore by remember { mutableStateOf(false) }
                LifecycleEventEffect(Lifecycle.Event.ON_START) {
                    localReminders.refresh()
                    todayWidget.refreshIfStale()
                    focusQuiet.cameBack()
                    if (startedBefore) dataChanges.cameBack() else startedBefore = true
                }
                // Leaving the front: the widget is what the person sees next, in the language and clock just chosen.
                LifecycleEventEffect(Lifecycle.Event.ON_STOP) { todayWidget.redrawSoon() }

                when (hasSession) {
                    null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    else -> CompositionLocalProvider(LocalStrings provides language.catalog, LocalDates provides dates) {
                        KadansNav(
                            startAtHome = hasSession == true,
                            languageController = languageController,
                            deepLinkRoute = parseDeepLink(deepLink),
                            leave = leave,
                        )
                        app.kadans.updates.UpdateRequiredDialog()
                    }
                }
            }
        }
    }
}

@Composable
private fun KadansNav(
    startAtHome: Boolean,
    languageController: LanguageController,
    deepLinkRoute: Any? = null,
    leave: ((message: String?) -> Unit)? = null,
) {
    // A text another app shared ("Share to Kadans"): its form is alone on the screen where the platform can hand the
    // person back afterwards, so that saving or leaving it returns to the app they shared from, not to Kadans' Home.
    val shared = (deepLinkRoute as? SharedTodoRoute)?.takeIf { leave != null }
    val backStack = remember {
        mutableStateListOf<Any>().also { stack ->
            when {
                !startAtHome -> stack.add(LoginRoute)
                shared != null -> stack.add(shared)
                else -> {
                    stack.add(HomeRoute)
                    // Home is already there: a link to it (the widget's header) adds nothing.
                    deepLinkRoute?.takeIf { it !is HomeRoute && opensWith(it, signedIn = true) }?.let { stack.add(it) }
                }
            }
        }
    }
    // Shared to a phone that is signed out: the form comes once the person has signed in, with what they shared.
    var sharedBeforeSignIn by remember { mutableStateOf(shared.takeIf { !startAtHome }) }

    fun resetTo(route: Any) {
        backStack.clear()
        backStack.add(route)
    }

    /** Where a sign-in leads: Home, or the form for what was shared to this phone while it was signed out. */
    fun signedIn() {
        resetTo(sharedBeforeSignIn ?: HomeRoute)
        sharedBeforeSignIn = null
    }

    // The four destinations: Home stays at the bottom of the stack, any other one sits alone on it, so Back from a
    // destination leads Home and Back from Home leaves the app. What was open over the last one closes.
    fun selectTab(tab: MainTab) {
        val home = backStack.indexOfFirst { it is HomeRoute }.takeIf { it >= 0 } ?: return resetTo(HomeRoute)
        while (backStack.size > home + 1) backStack.removeAt(backStack.lastIndex)
        when (tab) {
            MainTab.Home -> Unit
            MainTab.Calendar -> backStack.add(CalendarRoute)
            MainTab.Budget -> backStack.add(BudgetRoute)
            MainTab.Focus -> backStack.add(FocusRoute)
        }
    }

    @Composable
    fun Destination(tab: MainTab, title: String, add: AddAction? = null, content: @Composable (Modifier) -> Unit) =
        MainShell(
            tab = tab,
            title = title,
            onTab = ::selectTab,
            onNotifications = { backStack.add(NotificationsRoute) },
            onSettings = { backStack.add(SettingsRoute) },
            add = add,
            content = content,
        )

    // The server refused this device's session (signed out elsewhere, password changed, deactivated): back to
    // sign-in from wherever the person is, with a word about why.
    val api = koinInject<KadansApi>()
    val realtime = koinInject<KadansRealtime>()
    var sessionEnded by remember { mutableStateOf(false) }
    // The account was just closed for erasure here: the sign-in screen says until when it can be kept.
    var closedUntil by remember { mutableStateOf<kotlin.time.Instant?>(null) }
    fun offerToKeep(eraseAfter: kotlin.time.Instant, restoreToken: String) =
        backStack.add(KeepAccountRoute(eraseAfter.toEpochMilliseconds(), restoreToken))
    LaunchedEffect(api) {
        api.sessionEnded.collect {
            realtime.stop()
            sessionEnded = true
            // Shared to a phone whose session turned out to be over: the form comes back after the sign-in.
            (backStack.singleOrNull() as? SharedTodoRoute)?.let { sharedBeforeSignIn = it }
            resetTo(LoginRoute)
        }
    }
    // A link that reaches the running app (a reminder tapped while it is open) opens on top of where the person is.
    val tokenStore = koinInject<TokenStore>()
    LaunchedEffect(Unit) {
        IncomingLinks.links.collect { link ->
            val route = parseDeepLink(link) ?: return@collect
            if (!opensWith(route, signedIn = tokenStore.load() != null) || backStack.lastOrNull() == route) return@collect
            when (route) {
                is BudgetRoute -> selectTab(MainTab.Budget)
                // The widget's header: Home, and what was open over it closes.
                is HomeRoute -> selectTab(MainTab.Home)
                else -> backStack.add(route)
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        // Scope ViewModels to their nav entry: a screen gets a fresh ViewModel on every visit and the
        // old one is cleared when the entry is popped. Without this decorator every ViewModel lived in
        // the window/activity store and outlived its screen — a busy flag left set after a successful
        // save came back as a stuck spinner on the next visit, `init` blocks never re-ran (stale
        // forms, stale todo snapshots), and a re-login reused the previous session's ViewModels.
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = { key ->
            when (key) {
                is LoginRoute -> NavEntry(key) {
                    LoginScreen(
                        languageController = languageController,
                        onLoggedIn = {
                            sessionEnded = false
                            signedIn()
                        },
                        onMfaRequired = { mfaToken -> backStack.add(MfaRoute(mfaToken)) },
                        onRegister = { backStack.add(RegisterRoute) },
                        onForgotPassword = { backStack.add(ForgotPasswordRoute) },
                        sessionEnded = sessionEnded,
                        onDeletionScheduled = ::offerToKeep,
                        notice = closedUntil?.let {
                            val d = LocalStrings.current.deletion
                            d.closedNotice(d.date(it.toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault()).date))
                        },
                    )
                }
                is KeepAccountRoute -> NavEntry(key) {
                    KeepAccountScreen(
                        eraseAfter = kotlin.time.Instant.fromEpochMilliseconds(key.eraseAfterMillis),
                        restoreToken = key.restoreToken,
                        onKept = {
                            closedUntil = null
                            sessionEnded = false
                            signedIn()
                        },
                        onLeave = { resetTo(LoginRoute) },
                    )
                }
                is ForgotPasswordRoute -> NavEntry(key) {
                    ForgotPasswordScreen(onBack = { backStack.removeLastOrNull() })
                }
                is ResetPasswordRoute -> NavEntry(key) {
                    ResetPasswordScreen(
                        email = key.email,
                        token = key.token,
                        onDone = { resetTo(LoginRoute) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is MfaRoute -> NavEntry(key) {
                    MfaScreen(
                        mfaToken = key.mfaToken,
                        onDeletionScheduled = ::offerToKeep,
                        onVerified = {
                            sessionEnded = false
                            signedIn()
                        },
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
                    val s = LocalStrings.current
                    Destination(MainTab.Home, "Kadans", AddAction(s.newTodo) { backStack.add(CreateTodoRoute) }) { modifier ->
                        HomeScreen(
                            onLoggedOut = { resetTo(LoginRoute) },
                            onOpenTodo = { todoId -> backStack.add(TodoDetailRoute(todoId)) },
                            onPaywall = { resetTo(PaywallRoute) },
                            modifier = modifier,
                        )
                    }
                }
                is PaywallRoute -> NavEntry(key) {
                    PaywallScreen(
                        onUnlocked = { resetTo(HomeRoute) },
                        onSignedOut = { resetTo(LoginRoute) },
                    )
                }
                is NotificationsRoute -> NavEntry(key) {
                    NotificationsScreen(
                        onOpenTodo = { todoId -> backStack.add(TodoDetailRoute(todoId)) },
                        onOpenLink = { link -> parseDeepLink(link)?.let { backStack.add(it) } },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is FocusRoute -> NavEntry(key) {
                    Destination(MainTab.Focus, LocalStrings.current.nav.focus) { modifier -> FocusScreen(modifier) }
                }
                is CalendarRoute -> NavEntry(key) {
                    Destination(MainTab.Calendar, LocalStrings.current.calendar) { modifier ->
                        CalendarScreen(onOpenTodo = { todoId -> backStack.add(TodoDetailRoute(todoId)) }, modifier = modifier)
                    }
                }
                is BudgetRoute -> NavEntry(key) {
                    val s = LocalStrings.current
                    Destination(MainTab.Budget, s.budget, AddAction(s.nav.newMovement) { backStack.add(BudgetAddRoute) }) { modifier ->
                        BudgetScreen(onEditTransaction = { id -> backStack.add(BudgetEditRoute(id)) }, modifier = modifier)
                    }
                }
                is BudgetAddRoute -> NavEntry(key) {
                    BudgetAddScreen(
                        onSaved = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is BudgetEditRoute -> NavEntry(key) {
                    BudgetAddScreen(
                        onSaved = { backStack.removeLastOrNull() },
                        onBack = { backStack.removeLastOrNull() },
                        transactionId = key.transactionId,
                    )
                }
                is SettingsRoute, is SettingsPageRoute -> NavEntry(key) {
                    SettingsScreen(
                        languageController = languageController,
                        onLoggedOut = { resetTo(LoginRoute) },
                        onBack = { backStack.removeLastOrNull() },
                        page = (key as? SettingsPageRoute)?.page ?: SettingsPage.Main,
                        onOpen = { page -> backStack.add(SettingsPageRoute(page)) },
                        onAccountClosed = { eraseAfter ->
                            closedUntil = eraseAfter
                            resetTo(LoginRoute)
                        },
                    )
                }
                is CreateTodoRoute -> NavEntry(key) {
                    CreateTodoScreen(
                        onCreated = { todoId -> backStack.removeLastOrNull(); backStack.add(TodoDetailRoute(todoId)) },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                is SharedTodoRoute -> NavEntry(key) {
                    // Alone on the screen, another app's share opened it: saved or left, the person is back there
                    // (the system's Back does the same by itself). Anywhere else it is the form as usual.
                    val alone = leave != null && backStack.size == 1
                    val saved = LocalStrings.current.share.saved
                    SharedTodoScreen(
                        draft = TodoDraft(key.title, key.description),
                        onCreated = { todoId ->
                            if (alone) leave?.invoke(saved)
                            else {
                                backStack.removeLastOrNull()
                                backStack.add(TodoDetailRoute(todoId))
                            }
                        },
                        onBack = { if (alone) leave?.invoke(null) else backStack.removeLastOrNull() },
                        onPaywall = { resetTo(HomeRoute) },
                    )
                }
                is TodoDetailRoute -> NavEntry(key) {
                    TodoDetailScreen(
                        todoId = key.todoId,
                        onOpenPomodoro = { loop, handsFree, finishAt ->
                            backStack.add(PomodoroRoute(key.todoId, loop, handsFree, finishAt?.toEpochMilliseconds()))
                        },
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
                        finishAtMillis = key.finishAtMillis,
                        startsOne = key.startsOne,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                else -> error("Unknown route: $key")
            }
        },
    )
}
