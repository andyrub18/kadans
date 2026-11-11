package app.kadans.api

import app.kadans.desktop.DesktopNetwork
import io.ktor.client.engine.HttpClientEngine

/** A company network's proxy and certificate authority (DesktopNetwork). */
actual fun platformHttpEngine(): HttpClientEngine? = DesktopNetwork.engine()
