package app.kadans.api

import io.ktor.client.engine.HttpClientEngine

/** The HTTP engine this platform's clients use; null for Ktor's own (phones: the system's proxy and authorities already). */
expect fun platformHttpEngine(): HttpClientEngine?
