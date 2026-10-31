package app.kadans.ui

import app.kadans.api.model.DevicePlatform
import app.kadans.ui.settings.opensAuthenticatorHere
import app.kadans.ui.settings.qrModules
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.google.zxing.qrcode.decoder.QRCodeDecoderMetaData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two-factor enrollment's QR code (Settings): read back as a scanner would, it is the server's otpauth:// address,
 * exactly, and the right way round (some phone scanners refuse a mirrored code; zxing reads one but says so).
 */
class TwoFactorQrTests {
    /** As the server builds it (AccountSecurity.StartMfaEnrollment): a 32-letter key, the address escaped. */
    private val address = "otpauth://totp/Kadans:marie.desrosiers%2Bkadans%40example.com" +
        "?secret=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP&issuer=Kadans&digits=6"

    private fun read(text: String): Pair<String, Boolean> {
        val modules = qrModules(text)
        val matrix = BitMatrix(modules.size)
        modules.forEachIndexed { row, squares -> squares.forEachIndexed { col, dark -> if (dark) matrix.set(col, row) } }
        val result = Decoder().decode(matrix)
        return result.text to ((result.other as? QRCodeDecoderMetaData)?.isMirrored == true)
    }

    @Test
    fun the_code_reads_back_as_the_servers_address_the_right_way_round() {
        val (text, mirrored) = read(address)

        assertEquals(address, text)
        assertFalse(mirrored)
    }

    @Test
    fun a_long_address_still_fits_and_reads_back() {
        val long = address.replace("marie.desrosiers%2Bkadans", "a".repeat(120))

        assertEquals(long, read(long).first)
    }

    @Test
    fun the_open_button_is_for_phones_whose_authenticator_is_on_the_device() {
        assertTrue(opensAuthenticatorHere(DevicePlatform.Android))
        assertTrue(opensAuthenticatorHere(DevicePlatform.Ios))
        assertFalse(opensAuthenticatorHere(DevicePlatform.Linux))
        assertFalse(opensAuthenticatorHere(DevicePlatform.Windows))
    }
}
