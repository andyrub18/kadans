package app.kadans.sound

// A phone's notifications carry their sound: each channel has its own (KadansChannels), which only the person changes.
actual fun platformSoundPlayer(): SoundPlayer = NoSoundPlayer
