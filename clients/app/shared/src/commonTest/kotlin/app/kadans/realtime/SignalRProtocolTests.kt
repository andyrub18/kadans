package app.kadans.realtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SignalRProtocolTests {
    private val rs = SignalRProtocol.RECORD_SEPARATOR

    @Test
    fun framesSplitAcrossChunksReassemble() {
        val buffer = StringBuilder()
        assertTrue(SignalRProtocol.extractFrames(buffer, "{}$rs{\"type\":6").single() == "{}")
        // The ping completes in the next websocket message, followed by a partial invocation.
        val frames = SignalRProtocol.extractFrames(buffer, "}$rs{\"type\":1,")
        assertEquals(listOf("{\"type\":6}"), frames)
        assertEquals("{\"type\":1,", buffer.toString())
    }

    @Test
    fun invocationCarriesTargetAndPayload() {
        val message = SignalRProtocol.parse(
            """{"type":1,"target":"pomodoro.run.changed","arguments":[{"id":"abc","status":"Active"}]}"""
        )
        val invocation = assertIs<SignalRProtocol.Message.Invocation>(message)
        assertEquals("pomodoro.run.changed", invocation.target)
        assertEquals(1, invocation.arguments.size)
    }

    @Test
    fun pingCloseAndHandshakeAckAreRecognized() {
        assertIs<SignalRProtocol.Message.Ping>(SignalRProtocol.parse("""{"type":6}"""))
        val close = assertIs<SignalRProtocol.Message.Close>(SignalRProtocol.parse("""{"type":7,"error":"bye"}"""))
        assertEquals("bye", close.error)
        // The `{}` handshake ack and malformed junk both land in Other, never crash.
        assertIs<SignalRProtocol.Message.Other>(SignalRProtocol.parse("{}"))
        assertIs<SignalRProtocol.Message.Other>(SignalRProtocol.parse("not json"))
    }

    @Test
    fun handshakeFrameIsTerminated() {
        assertTrue(SignalRProtocol.HANDSHAKE.endsWith(rs))
        assertTrue(SignalRProtocol.HANDSHAKE.startsWith("""{"protocol":"json","version":1}"""))
    }
}
