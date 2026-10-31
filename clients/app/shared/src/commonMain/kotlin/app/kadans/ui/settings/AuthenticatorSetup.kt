package app.kadans.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.kadans.api.model.DevicePlatform
import app.kadans.config.devicePlatform
import app.kadans.i18n.LocalStrings
import kotlin.math.floor
import qrcode.raw.ErrorCorrectionLevel
import qrcode.raw.QRCodeProcessor

/**
 * Two-factor enrollment's QR code (Settings → Two-factor authentication): the server's `otpauth://` address, which an
 * authenticator app scans to add Kadans, instead of the key typed by hand. The squares are placed by their own row and
 * column, so the code is never drawn mirrored (a test reads it back as a scanner would).
 */
internal fun qrModules(text: String): List<BooleanArray> {
    val squares = QRCodeProcessor(text, ErrorCorrectionLevel.MEDIUM).encode()
    val size = squares.size
    val modules = List(size) { BooleanArray(size) }
    squares.forEach { row -> row.forEach { square -> modules[square.row][square.col] = square.dark } }
    return modules
}

/** The empty border a scanner needs around a QR code, in squares (the standard's four). */
private const val QUIET_ZONE = 4

/**
 * The code, square, black on white in both themes (scanners expect dark on light), with its quiet zone. Each square is
 * a whole number of pixels, and so is where the code starts: a fractional edge comes out grey, and a scanner misreads
 * the squares (it did, in a test render, before the start was rounded).
 */
@Composable
internal fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Canvas(modifier.aspectRatio(1f).semantics { contentDescription = description }) {
        val count = modules.size + 2 * QUIET_ZONE
        val cell = floor(size.minDimension / count).coerceAtLeast(1f)
        val origin = floor((size.minDimension - cell * count) / 2) + cell * QUIET_ZONE
        drawRect(Color.White)
        modules.forEachIndexed { row, squares ->
            squares.forEachIndexed { col, dark ->
                if (dark) drawRect(Color.Black, Offset(origin + col * cell, origin + row * cell), Size(cell, cell))
            }
        }
    }
}

/** Phones keep the authenticator app on the device itself: a button opens it there, as no one can scan their own screen. */
internal fun opensAuthenticatorHere(platform: DevicePlatform): Boolean =
    platform == DevicePlatform.Android || platform == DevicePlatform.Ios

/**
 * The first half of turning two-factor on: what to do, the QR code (and on a phone the button that hands the address to
 * the authenticator app there), and the key, with a copy button, for an app that takes it typed. The code field and
 * Verify follow it in Settings.
 */
@Composable
internal fun AuthenticatorEnrollment(
    sharedKey: String,
    authenticatorUri: String,
    onPhone: Boolean = remember { opensAuthenticatorHere(devicePlatform()) },
) {
    val s = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    @Suppress("DEPRECATION") // LocalClipboard's ClipEntry is built differently on each platform; plain text is all this needs
    val clipboard = LocalClipboardManager.current
    var noAuthenticator by remember(authenticatorUri) { mutableStateOf(false) }
    var copied by remember(sharedKey) { mutableStateOf(false) }

    Text(if (onPhone) s.twoFactor.phoneHint else s.twoFactor.scanHint, style = MaterialTheme.typography.bodySmall)
    if (onPhone) {
        Button(
            onClick = { noAuthenticator = runCatching { uriHandler.openUri(authenticatorUri) }.isFailure },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(s.twoFactor.openAuthenticator) }
        if (noAuthenticator) {
            Text(s.twoFactor.noAuthenticator, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        QrCode(authenticatorUri, s.twoFactor.qrDescription, Modifier.widthIn(max = 260.dp).fillMaxWidth())
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.mfaManualKey, style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(sharedKey, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(sharedKey.filterNot(Char::isWhitespace)))
                copied = true
            }) { Text(if (copied) s.twoFactor.keyCopied else s.twoFactor.copyKey) }
        }
    }
}
