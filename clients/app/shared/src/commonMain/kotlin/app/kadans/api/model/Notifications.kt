@file:UseSerializers(IsoInstantSerializer::class)

package app.kadans.api.model

import app.kadans.api.IsoInstantSerializer
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

@Serializable
data class NotificationResponse(
    val id: String,
    val kind: String,
    val title: String,
    val body: String,
    val data: Map<String, String>? = null,
    val createdAt: Instant,
    val readAt: Instant? = null,
)

@Serializable
data class UnreadCountResponse(val unread: Int)
