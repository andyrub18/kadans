package app.kadans.ui.todos

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.kadans.i18n.LocalStrings
import app.kadans.reminders.LocalReminders
import org.koin.compose.koinInject

/**
 * Saving a todo with a reminder, the first time, on a phone that cannot ring reminders itself yet: what Android's
 * "Alarms & reminders" is for, asked once (ARCHITECTURE → "Reminders ring on the phone"). The todo is saved whatever
 * the answer; "Allow" then opens the system's screen. Either answer is final (Settings still offers it); a dialog
 * dismissed without one asks again next time.
 */
@Stable
class ReminderPermissionAsk internal constructor(private val reminders: LocalReminders) {
    var showing by mutableStateOf(false)
        private set

    private var pendingSave: (() -> Unit)? = null
    private var openAfterSave = false

    /** Saves now, or once the person has answered. */
    fun beforeSaving(notify: Boolean, save: () -> Unit) {
        if (notify && reminders.shouldOfferPermission()) {
            pendingSave = save
            showing = true
        } else {
            save()
        }
    }

    fun allow() = answer(open = true)

    fun notNow() = answer(open = false)

    fun dismiss() {
        showing = false
        pendingSave?.invoke()
        pendingSave = null
    }

    /** The todo is saved: the system's screen, if the person said yes. The window follows when the app comes back. */
    fun saved() {
        if (!openAfterSave) return
        openAfterSave = false
        reminders.openSettings()
    }

    private fun answer(open: Boolean) {
        reminders.permissionAnswered()
        openAfterSave = open
        dismiss()
    }
}

@Composable
fun rememberReminderPermissionAsk(): ReminderPermissionAsk {
    val reminders = koinInject<LocalReminders>()
    return remember(reminders) { ReminderPermissionAsk(reminders) }
}

@Composable
fun ReminderPermissionDialog(ask: ReminderPermissionAsk) {
    if (!ask.showing) return
    val r = LocalStrings.current.reminders
    AlertDialog(
        onDismissRequest = ask::dismiss,
        title = { Text(r.permissionTitle) },
        text = { Text(r.permissionText) },
        confirmButton = { TextButton(onClick = ask::allow) { Text(r.allow) } },
        dismissButton = { TextButton(onClick = ask::notNow) { Text(r.notNow) } },
    )
}
