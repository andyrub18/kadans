package app.kadans.auth

/**
 * What a device seals the session with (docs/ARCHITECTURE.md → "The session, sealed on the device"): Android's
 * Keystore, Windows' data protection, the Linux desktop's keyring. What comes out of [seal] opens on this device, for
 * this person, and nowhere else: a copy of the app's settings (a backup, a disk taken out, another account on the
 * computer) holds nothing that signs in.
 *
 * Neither function throws: each answer says what the store may do next.
 */
interface Sealer {
    suspend fun seal(plain: ByteArray): Sealing

    suspend fun open(sealed: ByteArray): Opening

    /** The person asks again after a refusal: the next call puts the question anew instead of remembering the answer. */
    suspend fun again() {}
}

sealed interface Sealing {
    class Done(val bytes: ByteArray) : Sealing

    /** This device has nothing to seal with (a Linux desktop without a keyring): the session is kept as it always was. */
    data object None : Sealing

    /**
     * It has, and it did not answer (a keyring left locked): nothing is written in the clear, the session lasts as
     * long as the app runs.
     */
    data object Refused : Sealing
}

sealed interface Opening {
    class Done(val bytes: ByteArray) : Opening

    /** Not this device's, or changed since it was sealed: it will never open. */
    data object Lost : Opening

    /** The device's protection could not be asked now: it may open at the next start. */
    data object Refused : Opening
}

/** A device with nothing to seal with: the session stays in the app's own settings. */
object NoSealer : Sealer {
    override suspend fun seal(plain: ByteArray): Sealing = Sealing.None

    override suspend fun open(sealed: ByteArray): Opening = Opening.Lost
}

/** This device's own protection, or [NoSealer]. */
expect fun platformSealer(): Sealer
