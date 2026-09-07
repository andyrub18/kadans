package app.kadans.ui.auth

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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import app.kadans.i18n.Language
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
private fun AuthScaffold(title: String, content: @Composable () -> Unit) {
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
    viewModel: LoginViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is LoginEvent.LoggedIn -> onLoggedIn()
                is LoginEvent.MfaRequired -> onMfaRequired(event.mfaToken)
            }
        }
    }

    val s = LocalStrings.current
    val currentLanguage by languageController.language.collectAsState()
    AuthScaffold(title = s.signInTitle) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Language.entries.forEach { lang ->
                FilterChip(
                    selected = currentLanguage == lang,
                    onClick = { languageController.set(lang) },
                    label = { Text(lang.tag.uppercase()) },
                )
            }
        }
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text(s.usernameOrEmail) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(s.password) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
        Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.signIn)
        }
        Row {
            TextButton(onClick = onRegister) { Text(s.createAccount) }
            TextButton(onClick = onForgotPassword) { Text(s.forgotPassword) }
        }
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
            OutlinedTextField(
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
            OutlinedTextField(
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
    viewModel: MfaViewModel = koinViewModel(key = "mfa-$mfaToken") { parametersOf(mfaToken) },
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(viewModel) { viewModel.verified.collect { onVerified() } }

    val s = LocalStrings.current
    AuthScaffold(title = s.twoFactorTitle) {
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text(s.mfaCodeLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
        Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.verify)
        }
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
        OutlinedTextField(
            value = state.username,
            onValueChange = viewModel::onUsernameChange,
            label = { Text(s.username) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(s.email) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.displayName,
            onValueChange = viewModel::onDisplayNameChange,
            label = { Text(s.displayNameOptional) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(s.password) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        ErrorText(if (state.error != null || state.errorCode != null) s.errorFor(state.errorCode, state.error) else null)
        Button(onClick = viewModel::submit, enabled = state.canSubmit, modifier = Modifier.fillMaxWidth()) {
            if (state.isLoading) CircularProgressIndicator(modifier = Modifier.padding(2.dp)) else Text(s.register)
        }
        TextButton(onClick = onBack) { Text(s.backToSignIn) }
    }
}
