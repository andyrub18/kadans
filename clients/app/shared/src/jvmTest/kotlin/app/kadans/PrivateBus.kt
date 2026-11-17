package app.kadans

import org.freedesktop.dbus.bin.EmbeddedDBusDaemon

/**
 * A session bus of a test's own (dbus-java's daemon) at [address], ready to take connections: no desktop needed, as
 * on CI.
 *
 * `startInBackgroundAndWait` returns once the socket is bound, which is a moment before the daemon's own thread
 * runs. A connection accepted in that moment ends the daemon's listening for good (dbus-java 5.2.1 leaves its accept
 * loop when the daemon "is not running", and closes the socket): that first connection is dropped at its first call
 * ("Disconnected") and the next ones find no socket. On a machine with a single free core, about one test in fifty
 * failed before it began. So this also waits for the daemon to be running, and makes no connection of its own.
 */
internal fun startPrivateBus(address: String): EmbeddedDBusDaemon {
    val daemon = EmbeddedDBusDaemon("$address,listen=true")
    daemon.startInBackgroundAndWait(10_000)
    val giveUpAt = System.nanoTime() + 10_000_000_000
    while (!daemon.isRunning) {
        check(System.nanoTime() < giveUpAt) { "the private bus did not start" }
        Thread.sleep(2)
    }
    return daemon
}
