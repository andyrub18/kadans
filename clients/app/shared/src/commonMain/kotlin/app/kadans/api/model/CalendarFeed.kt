package app.kadans.api.model

import kotlin.time.Instant
import kotlinx.serialization.Serializable

/**
 * The account's calendar feed (ARCHITECTURE → "The calendar feed"). On: [url] is the link a calendar app subscribes to,
 * always https and secret (whoever holds it reads the todos' titles and times); [lastFetchedAt] is the last time a
 * calendar read it, null while none has. [available]: false for an account without the phones' subscription, which the
 * feed is part of: it cannot be turned on, and a link it already has gives its calendars nothing until the
 * subscription is back.
 */
@Serializable
data class CalendarFeedResponse(
    val enabled: Boolean,
    val url: String? = null,
    val createdAt: Instant? = null,
    val lastFetchedAt: Instant? = null,
    val available: Boolean = true,
)
