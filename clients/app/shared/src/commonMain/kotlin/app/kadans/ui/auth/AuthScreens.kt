package app.kadans.ui.auth

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import app.kadans.ui.BigButton
import app.kadans.ui.SecondaryButton
import app.kadans.ui.ChoiceChip
import app.kadans.ui.KadansField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.kadans.config.ServerAddress
import app.kadans.i18n.Language
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AuthScaffold(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Kadans", style = MaterialTheme.typography.displaySmall)
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp, bottom = 24.dp))
        Column(
            modifier = Modifier.widthIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            content()
        }
    }
}

@Composable
private fun ErrorText(error: String?) {
    if (error != null) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun LoginScreen(
    languageController: LanguageController,
    onLoggedIn: () -> Unit,
    onMfaRequired: (String) -> Unit,
    onRegister: () -> Unit,
    onForgotPassword: () -> Unit,
    /** The server ended this device's session (signed out elsewhere, password changed): say so. */
    sessionEnded: Boolean = false,
    /** The account awaits erasure (just deleted here, or signed into): where to offer keeping it. */
    onDeletionScheduled: (eraseAfter: kotlin.time.Instant, restoreToken: String) -> Unit = { _, _ -> },
    /** A word above the form: the account was just closed for erasure. */
    notice: String? = null,
    /** A session is kept on this device and its protection did not open it (a keyring left locked): say so. */
    sessionWaiting: Boolean = false,
    /** "Retry" under that word: ask the device's protection again. */
    onRetrySession: () -> Unit = {},
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LoginEvent.LoggedIn -> onLoggedIn()
                is LoginEvent.MfaRequired -> onMfaRequired(event.mfaToken)
                is LoginEvent.DeletionScheduled -> onDeletionScheduled(event.eraseAfter, event.restoreToken)
            }
        }
    }

    val s = LocalStrings.current
    val currentLanguage by languageController.language.collectAsState()
    AuthScaffold(title = s.signInTitle) {
        if (notice != null) {
            Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        } else if (sessionWaiting) {
            Text(s.account.sessionWaitingNotice, style = MaterialTheme.typography.bodyMedium)
            SecondaryButton(onClick = onRetrySession) { Text(s.retry) }
        } else if (sessionEnded) {
            Text(s.account.sessionEndedNotice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.serverUnreachable) ServerUnreachable(checking = state.isCheckingServer, onRetry = viewModel::retryServer)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { lang ->
                ChoiceChip(
                    selected = currentLanguage == lang,
                    onClick = { languageController.set(lang) },
                    label = { Text(lang.tag.uppercase()) },
                )
            }
        }
        KadansField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text(s.usernameOrEmail) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        KadansField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(s.password) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        // "Could not reach the server" is on the notice above when the server is out of reach: not said twice.
        val said = state.serverUnreachable && state.errorCode == "network"
        ErrorText(if ((state.error != null || state.errorCode != null) && !said) s.errorFor(state.errorCode, state.error) else null)
        BigButton(s.signIn, viewModel::submit, Modifier.fillMaxWidth(), enabled = state.canSubmit, busy = state.isLoading)
        if (state.googleAvailable) {
            GoogleSignInButton(
                text = s.continueWithGoogle,
                onClick = viewModel::signInWithGoogle,
                enabled = !state.isLoading,
                loading = state.isGoogleLoading,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isGoogleLoading) {
                Text(s.googleWaiting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = viewModel::cancelGoogle) { Text(s.cancel) }
            }
        }
        Row {
            TextButton(onClick = onRegister) { Text(s.createAccount) }
            TextButton(onClick = onForgotPassword) { Text(s.forgotPassword) }
        }
        if (ServerAddress.adjustable) ServerAddressButton(onChanged = viewModel::refreshProviders)
    }
}

/**
 * The server gave no answer when the screen asked it: said at once, at the top of the form, with a way to ask again
 * now. It is asked again by itself too, and the notice goes with the first answer.
 */
@Composable
private fun ServerUnreachable(checking: Boolean, onRetry: () -> Unit) {
    val s = LocalStrings.current
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(20.dp),
        color = scheme.errorContainer,
        contentColor = scheme.onErrorContainer,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 6.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.errorFor("network", null), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (checking) {
                CircularProgressIndicator(Modifier.padding(horizontal = 18.dp).size(20.dp), color = scheme.onErrorContainer, strokeWidth = 2.5.dp)
            } else {
                TextButton(onClick = onRetry) { Text(s.retry, color = scheme.onErrorContainer) }
            }
        }
    }
}

/**
 * The pre-auth escape hatch of a dev build: a phone on Wi-Fi must point at the computer's LAN address
 * before it can sign in at all. Applies immediately (the API reads the address per request). A build made
 * for a server never shows it (ServerAddress).
 */
@Composable
private fun ServerAddressButton(onChanged: () -> Unit = {}) {
    val s = LocalStrings.current
    val settings = org.koin.compose.koinInject<com.russhwolf.settings.Settings>()
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var shown by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(ServerAddress.current(settings)) }

    TextButton(onClick = { editing = true }) {
        Text("\u2699 " + shown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (editing) {
        var value by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(ServerAddress.overrideOrNull(settings) ?: "")
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(s.serverLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KadansField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        placeholder = { Text(ServerAddress.builtInDefault()) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(s.serverHint, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    ServerAddress.setOverride(settings, value)
                    shown = ServerAddress.current(settings)
                    editing = false
                    onChanged() // another server may offer other sign-ins
                }) { Text(s.ok) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(s.cancel) } },
        )
    }
}

@Composable
fun ForgotPasswordScreen(
    onBack: () -> Unit,
    viewModel: ForgotPasswordViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    AuthScaffold(title = s.forgotTitle) {
        if (state.sent) {
            Text(s.resetEmailSent, style = MaterialTheme.typography.bodyMedium)
        } else {
            KadansField(
                value = state.email,
                onValueChange = { v -> viewModel.update { it.copy(email = v) } },
                label = { Text(s.email) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
            Button(
                onClick = viewModel::submit,
                enabled = state.email.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.sendResetLink)
            }
        }
        TextButton(onClick = onBack) { Text(s.backToSignIn) }
    }
}

@Composable
fun ResetPasswordScreen(
    email: String,
    token: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
    viewModel: ResetPasswordViewModel = koinViewModel(key = "reset-$email") { parametersOf(email, token) },
) {
    val state by viewModel.state.collectAsState()

    val s = LocalStrings.current
    AuthScaffold(title = s.resetPasswordTitle) {
        if (state.done) {
            Text(s.resetDone, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(s.signIn) }
        } else {
            Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            KadansField(
                value = state.newPassword,
                onValueChange = { v -> viewModel.update { it.copy(newPassword = v) } },
                label = { Text(s.newPassword) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
            Button(
                onClick = viewModel::submit,
                enabled = state.newPassword.isNotBlank() && !state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.resetPasswordAction)
            }
            TextButton(onClick = onBack) { Text(s.backToSignIn) }
        }
    }
}

@Composable
fun MfaScreen(
    mfaToken: String,
    onVerified: () -> Unit,
    onBack: () -> Unit,
    onDeletionScheduled: (eraseAfter: kotlin.time.Instant, restoreToken: String) -> Unit = { _, _ -> },
    viewModel: MfaViewModel = koinViewModel(key = "mfa-$mfaToken") { parametersOf(mfaToken) },
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.verified.collect { outcome ->
            if (outcome is LoginEvent.DeletionScheduled) onDeletionScheduled(outcome.eraseAfter, outcome.restoreToken) else onVerified()
        }
    }

    val s = LocalStrings.current
    AuthScaffold(title = s.twoFactorTitle) {
        KadansField(
            value = state.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text(s.mfaCodeLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
        BigButton(s.verify, viewModel::submit, Modifier.fillMaxWidth(), enabled = state.canSubmit, busy = state.isLoading)
        TextButton(onClick = onBack) { Text(s.back) }
    }
}

@Composable
fun RegisterScreen(
    onRegistered: () -> Unit,
    onBack: () -> Unit,
    viewModel: RegisterViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) { viewModel.registered.collect { onRegistered() } }

    val s = LocalStrings.current
    AuthScaffold(title = s.registerTitle) {
        KadansField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text(s.username) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        KadansField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(s.email) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        KadansField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = { Text(s.displayNameOptional) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        KadansField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(s.password) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
        BigButton(s.register, viewModel::submit, Modifier.fillMaxWidth(), enabled = state.canSubmit, busy = state.isLoading)
        TextButton(onClick = onBack) { Text(s.backToSignIn) }
    }
}
