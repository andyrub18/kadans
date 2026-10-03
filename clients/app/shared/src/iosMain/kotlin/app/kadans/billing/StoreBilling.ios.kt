package app.kadans.billing

/** StoreKit 2 comes with the iPhone app (it needs a Mac and the App Store setup): no store until then. */
actual fun platformStoreBilling(): StoreBilling = NoStoreBilling
