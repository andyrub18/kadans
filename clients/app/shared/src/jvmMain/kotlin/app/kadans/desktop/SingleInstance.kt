package app.kadans.desktop

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * One Kadans at a time for this person on this computer. Starting it again (from the launcher, while Kadans sits in the
 * tray) shows the running one's window and leaves, instead of running a second copy that would keep a second live
 * connection and ring every reminder twice. Dev and installed builds share the same settings (the tokens, the reminder
 * window), so they share this too.
 *
 * A lock file the system releases when the process ends, however it ends, and a Unix socket beside it on which any
 * connection means "show yourself". Both are in a folder only this user can open. Anything unexpected (no such folder,
 * a file system without locks) lets this start run: never a reason for Kadans not to open.
 */
class SingleInstance(private val dir: Path? = privateDir(), private val answerWithin: Duration = 5.seconds) : AutoCloseable {
    enum class Claim {
        /** No other Kadans: run. */
        Only,

        /** Another Kadans runs, and was asked to show its window unless the start was a quiet one: leave. */
        Other,

        /** Another Kadans holds the lock but never answered: leave (a second copy would ring every reminder twice). */
        OtherUnreachable,
    }

    private var lock: FileChannel? = null
    private var server: ServerSocketChannel? = null

    /**
     * [onShow] runs on a background thread each time another start asks. A quiet start ([showOther] false: the session
     * opening Kadans in the background) leaves a running Kadans as it is instead of showing its window.
     */
    fun claim(onShow: () -> Unit, showOther: Boolean = true): Claim {
        val folder = dir ?: return Claim.Only
        val socket = folder.resolve("instance.sock")
        val channel = try {
            if (!privateFolder(folder)) return Claim.Only
            FileChannel.open(folder.resolve("instance.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        } catch (e: Exception) {
            return Claim.Only
        }
        val held = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null // this process holds it already (two claims in one JVM: tests)
        } catch (e: IOException) {
            channel.close()
            return Claim.Only
        }
        if (held == null) {
            channel.close()
            return if (!showOther || askToShow(socket)) Claim.Other else Claim.OtherUnreachable
        }
        lock = channel
        server = listen(socket, onShow)
        return Claim.Only
    }

    /** The lock goes with the process anyway; this is for tests. */
    override fun close() {
        runCatching { server?.close() }
        runCatching { lock?.close() }
    }

    /** The connection is the request. Retried for a while: the other may have just taken the lock and not yet listen. */
    private fun askToShow(socket: Path): Boolean {
        val start = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                SocketChannel.open(UnixDomainSocketAddress.of(socket)).close()
                return true
            } catch (e: IOException) {
                if (start.elapsedNow() >= answerWithin) return false
                Thread.sleep(RETRY.inWholeMilliseconds)
            }
        }
    }

    private fun listen(socket: Path, onShow: () -> Unit): ServerSocketChannel? = try {
        // We hold the lock, so a socket left there is a previous Kadans's that ended without removing it.
        Files.deleteIfExists(socket)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(socket)).also { server ->
            thread(isDaemon = true, name = "kadans-single-instance") {
                while (true) {
                    val client = try {
                        server.accept()
                    } catch (e: IOException) {
                        break
                    }
                    runCatching { client.close() }
                    runCatching(onShow)
                }
            }
        }
    } catch (e: Exception) {
        null // a second start then cannot reach this one, but the lock still keeps it from running
    }

    internal companion object {
        private val RETRY = 100.milliseconds

        /** Linux: the session's runtime folder (private, cleared at logout). macOS and Windows: the user's own app data. */
        fun privateDir(): Path? {
            val os = System.getProperty("os.name").lowercase()
            val home = System.getProperty("user.home")
            return when {
                "linux" in os -> System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it, "kadans") }
                    ?: Path.of(home, ".cache", "kadans")
                "mac" in os -> Path.of(home, "Library", "Caches", "Kadans")
                "windows" in os -> System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Kadans") }
                else -> null
            }
        }

        /** Made if missing; where permissions exist, owned by this user and closed to everyone else (else not used). */
        fun privateFolder(dir: Path): Boolean {
            val posix = "posix" in dir.fileSystem.supportedFileAttributeViews()
            val ownerOnly = PosixFilePermissions.fromString("rwx------")
            if (Files.notExists(dir)) {
                if (posix) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(ownerOnly)) else Files.createDirectories(dir)
            }
            if (!posix) return Files.isDirectory(dir)
            if (Files.getOwner(dir).name != System.getProperty("user.name")) return false
            if (Files.getPosixFilePermissions(dir) != ownerOnly) Files.setPosixFilePermissions(dir, ownerOnly)
            return true
        }
    }
}
