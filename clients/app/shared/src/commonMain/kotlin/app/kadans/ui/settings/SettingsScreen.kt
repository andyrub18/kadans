package app.kadans.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.kadans.i18n.Language
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsScreen(
    languageController: LanguageController,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current
    val language by languageController.language.collectAsState()

    LaunchedEffect(viewModel) { viewModel.loggedOut.collect { onLoggedOut() } }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← " + s.back) }
                Text(s.settings, style = MaterialTheme.typography.headlineSmall)
            }

            if (state.error != null || state.errorCode != null) {
                Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            // ---- Profile ----
            Text(s.profileSection, style = MaterialTheme.typography.titleMedium)
            state.user?.email?.let { email ->
                Text(email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(
                value = state.username,
                onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                label = { Text(s.username) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.displayName,
                onValueChange = { v -> viewModel.update { it.copy(displayName = v) } },
                label = { Text(s.displayNameOptional) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.timeZone,
                onValueChange = { v -> viewModel.update { it.copy(timeZone = v) } },
                label = { Text(s.timeZoneLabel) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(s.languageLabel, style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Language.entries.forEach { candidate ->
                    FilterChip(
                        selected = language == candidate,
                        onClick = { languageController.set(candidate) },
                        label = { Text(candidate.displayName) },
                    )
                }
            }
            Button(onClick = viewModel::saveProfile, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(s.saveProfile)
            }
            if (state.profileSaved) {
                Text(s.profileSaved, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- Password ----
            Text(s.changePasswordTitle, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = state.currentPassword,
                onValueChange = { v -> viewModel.update { it.copy(currentPassword = v) } },
                label = { Text(s.currentPassword) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.newPassword,
                onValueChange = { v -> viewModel.update { it.copy(newPassword = v) } },
                label = { Text(s.newPassword) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(s.passwordChangedSignInAgain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = viewModel::changePassword,
                enabled = !state.isBusy && state.currentPassword.isNotBlank() && state.newPassword.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(s.changePasswordAction) }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- Two-factor ----
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(s.mfaSection, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state.user?.twoFactorEnabled == true) s.mfaEnabledBadge else s.mfaDisabledBadge,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.user?.twoFactorEnabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val enrollment = state.enrollment
            when {
                enrollment != null -> {
                    Text(s.mfaEnrollHint, style = MaterialTheme.typography.bodySmall)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s.mfaManualKey, style = MaterialTheme.typography.labelMedium)
                            SelectionContainer {
                                Text(enrollment.sharedKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            }
                            SelectionContainer {
                                Text(
                                    enrollment.authenticatorUri,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    MfaCodeField(state.mfaCode, viewModel)
                    Button(
                        onClick = viewModel::confirmMfa,
                        enabled = !state.isBusy && state.mfaCode.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(s.verify) }
                }
                state.user?.twoFactorEnabled == true -> {
                    MfaCodeField(state.mfaCode, viewModel)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = viewModel::regenerateRecoveryCodes,
                            enabled = !state.isBusy && state.mfaCode.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text(s.regenerateRecoveryCodes) }
                        OutlinedButton(
                            onClick = viewModel::disableMfa,
                            enabled = !state.isBusy && state.mfaCode.isNotBlank(),
                            modifier = Modifier.weight(1f),
                        ) { Text(s.disableMfa, color = MaterialTheme.colorScheme.error) }
                    }
                }
                else -> {
                    Button(onClick = viewModel::startMfaEnrollment, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(s.enableMfa)
                    }
                }
            }

            state.recoveryCodes?.let { codes ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.recoveryCodesTitle, style = MaterialTheme.typography.titleSmall)
                        Text(s.recoveryCodesHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text(
                                codes.joinToString("\n"),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            // ---- Sessions ----
            OutlinedButton(onClick = viewModel::signOutEverywhere, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(s.signOutEverywhere, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text(s.signOut)
            }
        }
    }
}

@Composable
private fun MfaCodeField(value: String, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    OutlinedTextField(
        value = value,
        onValueChange = { v -> viewModel.update { it.copy(mfaCode = v) } },
        label = { Text(s.mfaCodeLabel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
