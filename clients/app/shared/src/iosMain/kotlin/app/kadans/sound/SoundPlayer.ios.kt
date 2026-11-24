package app.kadans.sound

// The iPhone's notifications will carry their sound when its app is built.
actual fun platformSoundPlayer(): SoundPlayer = NoSoundPlayer
