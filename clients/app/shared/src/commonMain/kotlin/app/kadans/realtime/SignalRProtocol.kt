package app.kadans.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The slice of the SignalR JSON hub protocol our server uses. The hub never calls methods with
 * replies — it only pushes invocations — so a tiny hand-rolled codec beats a client library:
 * frames are JSON documents separated by 0x1E, and the handshake is one such frame each way.
 */
object SignalRProtocol {
    const val RECORD_SEPARATOR = '\u001E'

    /** First frame the client must send after the socket opens. */
    const val HANDSHAKE = """{"protocol":"json","version":1}$RECORD_SEPARATOR"""

    const val PING = """{"type":6}$RECORD_SEPARATOR"""

    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Message {
        /** The server invoked a client method: our two events plus anything future. */
        data class Invocation(val target: String, val arguments: List<JsonElement>) : Message

        data object Ping : Message

        data class Close(val error: String?) : Message

        /** The empty `{}` handshake ack, completions, or anything we don't act on. */
        data object Other : Message
    }

    /**
     * Appends [chunk] to [buffer] and returns every complete frame now available, leaving any
     * unterminated tail in the buffer. A frame may arrive split across websocket messages.
     */
    fun extractFrames(buffer: StringBuilder, chunk: String): List<String> {
        buffer.append(chunk)
        val frames = mutableListOf<String>()
        while (true) {
            val end = buffer.indexOf(RECORD_SEPARATOR)
            if (end < 0) break
            frames.add(buffer.substring(0, end))
            buffer.deleteRange(0, end + 1)
        }
        return frames
    }

    fun parse(frame: String): Message {
        val root = try {
            json.parseToJsonElement(frame).jsonObject
        } catch (_: Exception) {
            return Message.Other
        }
        return when (root["type"]?.jsonPrimitive?.intOrNull) {
            1 -> Message.Invocation(
                target = root["target"]?.jsonPrimitive?.content ?: return Message.Other,
                arguments = root["arguments"]?.jsonArray ?: emptyList(),
            )
            6 -> Message.Ping
            7 -> Message.Close(root["error"]?.jsonPrimitive?.content)
            else -> Message.Other
        }
    }
}
