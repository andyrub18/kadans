package app.kadans.auth

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The session sealed on Windows (docs/ARCHITECTURE.md → "The session, sealed on the device") with the system's own
 * data protection (DPAPI, `CryptProtectData`): Windows encrypts with a key it derives from the person's sign-in, so
 * what is sealed opens for that person on that computer and for nobody else: not another account, not a copy of the
 * registry, not the disk in another machine. It is what browsers and Windows' own credential store rest on. No window
 * is ever shown, nothing is asked.
 */
internal class WindowsSealer : Sealer {
    override suspend fun seal(plain: ByteArray): Sealing = withContext(Dispatchers.IO) {
        runCatching { Dpapi.protect(plain) }.getOrNull()?.let { Sealing.Done(it) } ?: Sealing.None
    }

    override suspend fun open(sealed: ByteArray): Opening = withContext(Dispatchers.IO) {
        try {
            // Windows refuses what it did not seal for this person, and what changed since.
            Dpapi.unprotect(sealed)?.let { Opening.Done(it) } ?: Opening.Lost
        } catch (_: Throwable) {
            Opening.Refused // the call itself could not be made
        }
    }
}

/** `CryptProtectData` and `CryptUnprotectData` (crypt32.dll), called through Java's own bridge to native code. */
internal object Dpapi {
    /** Never show a window: Kadans seals in the background too. */
    private const val UI_FORBIDDEN = 0x1

    /** Mixed into the sealing: what another program sealed for the same person does not open as Kadans' session. */
    private val PURPOSE = "app.kadans.session".toByteArray()

    /** `DATA_BLOB { DWORD cbData; BYTE *pbData; }` on 64-bit Windows. */
    private val BLOB = MemoryLayout.structLayout(JAVA_INT.withName("cbData"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pbData"))
    private const val POINTER_AT = 8L

    private val linker = Linker.nativeLinker()
    private val crypt32 by lazy { SymbolLookup.libraryLookup("Crypt32", Arena.global()) }
    private val kernel32 by lazy { SymbolLookup.libraryLookup("Kernel32", Arena.global()) }

    // BOOL f(DATA_BLOB* in, LPCWSTR description, DATA_BLOB* entropy, PVOID reserved, PROMPTSTRUCT* prompt, DWORD flags, DATA_BLOB* out)
    private val call = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS)
    private val protect: MethodHandle by lazy { linker.downcallHandle(crypt32.find("CryptProtectData").orElseThrow(), call) }
    private val unprotect: MethodHandle by lazy { linker.downcallHandle(crypt32.find("CryptUnprotectData").orElseThrow(), call) }
    private val localFree: MethodHandle by lazy {
        linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS))
    }

    /** Sealed for the person signed in to Windows. Throws when Windows seals nothing. */
    fun protect(plain: ByteArray): ByteArray = run(protect, plain) ?: error("CryptProtectData failed")

    /** Null: not sealed here for this person, or changed since. */
    fun unprotect(sealed: ByteArray): ByteArray? = run(unprotect, sealed)

    private fun run(function: MethodHandle, input: ByteArray): ByteArray? = Arena.ofConfined().use { arena ->
        val out = arena.allocate(BLOB)
        val done = function.invokeWithArguments(
            blob(arena, input), MemorySegment.NULL, blob(arena, PURPOSE), MemorySegment.NULL, MemorySegment.NULL, UI_FORBIDDEN, out,
        ) as Int
        if (done == 0) return null
        // Windows allocated the answer: copied out, then given back.
        val answer = out.get(ADDRESS, POINTER_AT)
        try {
            answer.reinterpret(out.get(JAVA_INT, 0).toLong()).toArray(JAVA_BYTE)
        } finally {
            localFree.invokeWithArguments(answer)
        }
    }

    private fun blob(arena: Arena, bytes: ByteArray): MemorySegment {
        val data = arena.allocate(bytes.size.toLong().coerceAtLeast(1))
        MemorySegment.copy(bytes, 0, data, JAVA_BYTE, 0, bytes.size)
        return arena.allocate(BLOB).also {
            it.set(JAVA_INT, 0, bytes.size)
            it.set(ADDRESS, POINTER_AT, data)
        }
    }
}
