package app.kadans.billing

/** The desktop app is free: no store. */
actual fun platformStoreBilling(): StoreBilling = NoStoreBilling
