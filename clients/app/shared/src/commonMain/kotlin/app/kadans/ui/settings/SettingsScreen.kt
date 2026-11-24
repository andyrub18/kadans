package app.kadans.ui.settings

import app.kadans.ui.PageHeader
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.BoxWithConstraints
import app.kadans.ui.SecondaryButton
import app.kadans.ui.ChoiceChip
import app.kadans.ui.Choices
import app.kadans.ui.Segmented
import app.kadans.ui.KadansField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kadans.api.model.ClockFormat
import app.kadans.api.model.UserResponse
import app.kadans.files.FilePorts
import app.kadans.i18n.Language
import app.kadans.i18n.LanguageController
import app.kadans.i18n.LocalStrings
import app.kadans.i18n.StringsCatalog
import app.kadans.ui.Appearance
import app.kadans.ui.AppearancePreference
import app.kadans.ui.LocalDates
import app.kadans.ui.billing.SubscriptionSection
import app.kadans.ui.shell.KadansIcons
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

/**
 * Settings' pages. The main one holds the account (and Sign out), how the app reads (language, time format, time
 * zone) and what belongs to this device; each account matter opens its own page from a row.
 */
enum class SettingsPage { Main, Profile, EmailAndPassword, TwoFactor, Devices, Calendar, MyData, DeleteAccount }

@Composable
fun SettingsScreen(
    languageController: LanguageController,
    onLoggedOut: () -> Unit,
    onBack: () -> Unit,
    page: SettingsPage = SettingsPage.Main,
    onOpen: (SettingsPage) -> Unit = {},
    onAccountClosed: (eraseAfter: kotlin.time.Instant) -> Unit = { onLoggedOut() },
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val s = LocalStrings.current

    LaunchedEffect(viewModel) { viewModel.loggedOut.collect { onLoggedOut() } }
    LaunchedEffect(viewModel) { viewModel.accountClosed.collect { onAccountClosed(it) } }
    // Back from a page that changed something (the name, the email, two-factor): shown as it is now.
    var shownBefore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (shownBefore) viewModel.refresh() else shownBefore = true }

    Column(Modifier.fillMaxSize()) {
        PageHeader(page.title(s), onBack)
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.widthIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.error != null || state.errorCode != null) {
                        Text(s.errorFor(state.errorCode, state.error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    when (page) {
                        SettingsPage.Main -> MainPage(state, viewModel, languageController, onOpen)
                        SettingsPage.Profile -> ProfilePage(state, viewModel)
                        SettingsPage.EmailAndPassword -> {
                            EmailSection(state, viewModel)
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            PasswordSection(state, viewModel)
                        }
                        SettingsPage.TwoFactor -> TwoFactorSection(state, viewModel)
                        SettingsPage.Devices -> DevicesSection(state, viewModel)
                        SettingsPage.Calendar -> CalendarFeedPage()
                        SettingsPage.MyData -> DataExportPage()
                        SettingsPage.DeleteAccount -> DeleteAccountSection(state, viewModel, onOpen)
                    }
                }
            }
        }
    }
}

private fun SettingsPage.title(s: StringsCatalog): String = when (this) {
    SettingsPage.Main -> s.settings
    SettingsPage.Profile -> s.profileSection
    SettingsPage.EmailAndPassword -> s.nav.emailAndPassword
    SettingsPage.TwoFactor -> s.mfaSection
    SettingsPage.Devices -> s.account.devicesSection
    SettingsPage.Calendar -> s.calendarFeed.title
    SettingsPage.MyData -> s.dataExport.section
    SettingsPage.DeleteAccount -> s.deletion.section
}

@Composable
private fun MainPage(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    languageController: LanguageController,
    onOpen: (SettingsPage) -> Unit,
) {
    val s = LocalStrings.current
    val language by languageController.language.collectAsState()

    state.user?.let { AccountCard(it, onSignOut = viewModel::signOut) }

    // Language, time format, appearance and time zone apply as soon as they are chosen.
    Text(s.languageLabel, style = MaterialTheme.typography.labelLarge)
    // Four names do not fit on one line of a phone: they wrap.
    Choices {
        Language.entries.forEach { candidate ->
            ChoiceChip(
                selected = language == candidate,
                onClick = { languageController.set(candidate) },
                label = { Text(candidate.displayName) },
            )
        }
    }
    Text(s.dates.clockSection, style = MaterialTheme.typography.labelLarge)
    val clock = LocalDates.current.clock
    Segmented(
        options = listOf(ClockFormat.TwelveHour, ClockFormat.TwentyFourHour),
        selected = clock,
        name = { if (it == ClockFormat.TwelveHour) s.dates.clock12 else s.dates.clock24 },
        onSelect = { if (clock != it) viewModel.setClock(it) },
        enabled = !state.isBusy,
    )
    Text(s.dates.clockHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    AppearanceChoice()
    TimeZoneSection(state, viewModel)

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // What belongs to this device, where it applies (each brings its own divider).
    RemindersSection()
    SoundSection()
    QuietSection()
    PhoneCalendarSection()
    WidgetSection()
    StartAtLoginSection()
    SubscriptionSection()

    SettingsRow(s.profileSection, onClick = { onOpen(SettingsPage.Profile) })
    SettingsRow(s.nav.emailAndPassword, onClick = { onOpen(SettingsPage.EmailAndPassword) })
    SettingsRow(
        s.mfaSection,
        onClick = { onOpen(SettingsPage.TwoFactor) },
        trailing = if (state.user?.twoFactorEnabled == true) s.mfaEnabledBadge else s.mfaDisabledBadge,
    )
    SettingsRow(s.account.devicesSection, onClick = { onOpen(SettingsPage.Devices) })
    SettingsRow(s.calendarFeed.section, onClick = { onOpen(SettingsPage.Calendar) })
    // A copy of the person's data goes out through the system's "Save" dialog: where the device has none, no row.
    if (koinInject<FilePorts>().available) SettingsRow(s.dataExport.section, onClick = { onOpen(SettingsPage.MyData) })
    SettingsRow(s.deletion.section, onClick = { onOpen(SettingsPage.DeleteAccount) }, danger = true)
}

/** Light or dark on this device: the system's by default, which a computer or a phone may override. */
@Composable
private fun AppearanceChoice(preference: AppearancePreference = koinInject()) {
    val a = LocalStrings.current.appearance
    val chosen by preference.appearance.collectAsState()
    Text(a.section, style = MaterialTheme.typography.labelLarge)
    Segmented(
        options = listOf(Appearance.System, Appearance.Light, Appearance.Dark),
        selected = chosen,
        name = {
            when (it) {
                Appearance.System -> a.system
                Appearance.Light -> a.light
                Appearance.Dark -> a.dark
            }
        },
        onSelect = { preference.set(it) },
    )
}

/** Who is signed in, and the way out: the first thing on the page. */
@Composable
private fun AccountCard(user: UserResponse, onSignOut: () -> Unit) {
    val s = LocalStrings.current
    val name = user.displayName?.takeIf { it.isNotBlank() } ?: user.username
    Card(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            val who: @Composable RowScope.() -> Unit = {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        user.email ?: user.username,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val signOut: @Composable () -> Unit = {
                SecondaryButton(onClick = onSignOut) { Text(s.signOut, color = MaterialTheme.colorScheme.error) }
            }
            // On a narrow phone "Cerrar sesión" beside the address left it four letters: the button goes under it.
            if (maxWidth >= 380.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    who()
                    signOut()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.End) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically, content = who)
                    signOut()
                }
            }
        }
    }
}

/** A row that opens one of Settings' pages. */
@Composable
private fun SettingsRow(label: String, onClick: () -> Unit, trailing: String? = null, danger: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(KadansIcons.Next, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ProfilePage(state: SettingsUiState, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    KadansField(
        value = state.username,
        onValueChange = { v -> viewModel.update { it.copy(username = v) } },
        label = { Text(s.username) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    KadansField(
        value = state.displayName,
        onValueChange = { v -> viewModel.update { it.copy(displayName = v) } },
        label = { Text(s.displayNameOptional) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = viewModel::saveProfile, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
        Text(s.saveProfile)
    }
    if (state.profileSaved) {
        Text(s.profileSaved, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmailSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    val a = s.account
    Text(a.emailSection, style = MaterialTheme.typography.titleMedium)
    val currentEmail = state.user?.email
    if (currentEmail != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SelectionContainer { Text(currentEmail, style = MaterialTheme.typography.bodyMedium) }
            Text(
                if (state.user?.emailConfirmed == true) a.emailConfirmed else a.emailNotConfirmed,
                style = MaterialTheme.typography.labelLarge,
                color = if (state.user?.emailConfirmed == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (state.user?.emailConfirmed != true) {
            TextButton(onClick = viewModel::resendConfirmation, enabled = !state.isBusy) { Text(a.resendConfirmation) }
            if (state.confirmationResent) {
                Text(a.confirmationSent, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else {
        Text(a.noEmailYet, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    KadansField(
        value = state.newEmail,
        onValueChange = { v -> viewModel.update { it.copy(newEmail = v, emailChangeSentTo = null) } },
        label = { Text(a.newEmailLabel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.user?.hasPassword == true) {
        KadansField(
            value = state.emailPassword,
            onValueChange = { v -> viewModel.update { it.copy(emailPassword = v) } },
            label = { Text(s.currentPassword) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(a.emailChangeHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecondaryButton(onClick = viewModel::requestEmailChange, enabled = state.canRequestEmailChange, modifier = Modifier.fillMaxWidth()) {
        Text(a.sendEmailChangeLink)
    }
    state.emailChangeSentTo?.let { sentTo ->
        Text(a.emailChangeSent(sentTo), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PasswordSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    Text(s.changePasswordTitle, style = MaterialTheme.typography.titleMedium)
    KadansField(
        value = state.currentPassword,
        onValueChange = { v -> viewModel.update { it.copy(currentPassword = v) } },
        label = { Text(s.currentPassword) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    KadansField(
        value = state.newPassword,
        onValueChange = { v -> viewModel.update { it.copy(newPassword = v) } },
        label = { Text(s.newPassword) },
        singleLine = true,
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )
    Text(s.passwordChangedSignInAgain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SecondaryButton(
        onClick = viewModel::changePassword,
        enabled = !state.isBusy && state.currentPassword.isNotBlank() && state.newPassword.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) { Text(s.changePasswordAction) }
}

@Composable
private fun TwoFactorSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    Text(
        if (state.user?.twoFactorEnabled == true) s.mfaEnabledBadge else s.mfaDisabledBadge,
        style = MaterialTheme.typography.labelLarge,
        color = if (state.user?.twoFactorEnabled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val enrollment = state.enrollment
    when {
        enrollment != null -> {
            AuthenticatorEnrollment(enrollment.sharedKey, enrollment.authenticatorUri)
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
                SecondaryButton(
                    onClick = viewModel::regenerateRecoveryCodes,
                    enabled = !state.isBusy && state.mfaCode.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text(s.regenerateRecoveryCodes) }
                SecondaryButton(
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
}

@Composable
private fun DevicesSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    val a = s.account
    Text(a.devicesHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.devices.isEmpty()) {
        Text(a.noDevices, style = MaterialTheme.typography.bodySmall)
    }
    val zone = androidx.compose.runtime.remember { kotlinx.datetime.TimeZone.currentSystemDefault() }
    state.devices.forEach { device ->
        val isThis = device.installationId == state.thisInstallationId
        Card(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(device.name + if (isThis) " · " + a.thisDevice else "", style = MaterialTheme.typography.titleSmall)
                    Text(
                        device.platform.name + " · " + (if (device.hasPushToken) a.pushOn else a.pushOff),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        a.lastSeen + " " + LocalDates.current.moment(device.lastSeenAt, zone),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Removing the device you are holding would only re-add it at the next launch.
                if (!isThis) {
                    TextButton(onClick = { viewModel.removeDevice(device.installationId) }, enabled = !state.isBusy) {
                        Text(a.removeDevice, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    HorizontalDivider(Modifier.padding(vertical = 8.dp))

    // Every session at once, this one included: a lost phone, a shared computer.
    SecondaryButton(onClick = viewModel::signOutEverywhere, enabled = !state.isBusy, modifier = Modifier.fillMaxWidth()) {
        Text(s.signOutEverywhere, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun DeleteAccountSection(state: SettingsUiState, viewModel: SettingsViewModel, onOpen: (SettingsPage) -> Unit) {
    val s = LocalStrings.current
    val d = s.deletion
    var confirming by androidx.compose.runtime.remember { mutableStateOf(false) }

    Text(d.explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(d.subscriptionNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // What is erased can be kept first: the way to "My data", where the device can save a file.
    if (koinInject<FilePorts>().available) {
        TextButton(onClick = { onOpen(SettingsPage.MyData) }) { Text(s.dataExport.beforeDeleting) }
    }
    if (state.user?.hasPassword == true) {
        KadansField(
            value = state.deletePassword,
            onValueChange = { v -> viewModel.update { it.copy(deletePassword = v) } },
            label = { Text(s.currentPassword) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    SecondaryButton(onClick = { confirming = true }, enabled = state.canDeleteAccount, modifier = Modifier.fillMaxWidth()) {
        Text(d.deleteAccount, color = MaterialTheme.colorScheme.error)
    }
    state.deletionLinkSentTo?.let { address ->
        Text(d.linkSent(address), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }

    if (confirming) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(d.confirmTitle) },
            text = { Text(d.confirmText) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    viewModel.deleteAccount()
                }) { Text(d.deleteAccount, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(s.cancel) } },
        )
    }
}

@Composable
private fun MfaCodeField(value: String, viewModel: SettingsViewModel) {
    val s = LocalStrings.current
    KadansField(
        value = value,
        onValueChange = { v -> viewModel.update { it.copy(mfaCode = v) } },
        label = { Text(s.mfaCodeLabel) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}
