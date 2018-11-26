package app.graviton.shell

import javafx.application.Platform
import okhttp3.HttpUrl
import org.eclipse.aether.artifact.Artifact
import java.io.IOException
import java.io.InputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import kotlin.coroutines.experimental.buildIterator

// TODO: Start a utilities project for these sorts of things.

/** Casts the string to a [Path] but does not check for existence or do any other form of disk IO. */
fun String.toPath(): Path = Paths.get(this)

/** Returns true if the given path exists. */
val Path.exists: Boolean get() = Files.exists(this)

/** Allows you to write paths like "foo".toPath() / "b" / "c" and will use the correct platform specific path concatenation rules. */
operator fun Path.div(other: String): Path = resolve(other)

/** The supported operating systems we are on and OS-specific settings. */
enum class OperatingSystem {
    MAC {
        private val library = homeDirectory / "Library"
        override val appCacheDirectory: Path get() = localM2Or(library / "Caches" / "Graviton Browser")
        override val loggingDirectory: Path get() = library / "Logs" / "Graviton Browser"
    },
    WIN {
        override val classPathDelimiter: String = ";"
        private val localAppData get() = System.getenv("LOCALAPPDATA").toPath()
        private val myLocalAppData get() = localAppData / "GravitonBrowser"
        // If you add new directories here, remember to update GravitonBrowser.iss to ensure the uninstaller removes them.
        override val appCacheDirectory: Path get() = myLocalAppData / "Cache"
        override val loggingDirectory: Path get() = myLocalAppData / "Logs"
    },
    LINUX {
        private val appDirectory: Path
            get() {
                return if (System.getenv("XDG_CACHE_HOME").isNullOrBlank()) {
                    homeDirectory / ".cache" / "GravitonBrowser"
                } else {
                    System.getenv("XDG_CACHE_HOME").toPath() / "GravitonBrowser"
                }
            }
        override val appCacheDirectory: Path get() = localM2Or(appDirectory / "repository")
        override val loggingDirectory: Path get() = appDirectory / "logs"
    },
    UNKNOWN {
        override val appCacheDirectory: Path get() = unreachable()
        override val loggingDirectory: Path get() = unreachable()
    };

    abstract val appCacheDirectory: Path
    abstract val loggingDirectory: Path
    open val classPathDelimiter: String = ":"
    open val homeDirectory: Path = System.getProperty("user.home").toPath()

    // Returns ~/.m2 if it exists on Mac/Linux or the "proper" Graviton-specific cache path if not. Useful for developers who already
    // have a local Maven repo and would like to reuse it, in particular this makes gradle publishToMavenLocal a lot more useful.
    protected fun localM2Or(path: Path): Path {
        val p = homeDirectory / ".m2" / "repository"
        return if (p.exists) p else path
    }
}

/** Creates the given path if necessary as a directory and returns it */
fun Path.createDirectories(): Path = Files.createDirectories(this)

/** Throws an exception indicating this code path should never be called. */
fun unreachable(): Nothing = error("Unreachable")

private val detectedOperatingSystemOverrideForTesting = InheritableThreadLocal<OperatingSystem?>()

fun <T> withOverriddenOperatingSystem(os: OperatingSystem, block: () -> T): T {
    val old = detectedOperatingSystemOverrideForTesting.get()
    try {
        detectedOperatingSystemOverrideForTesting.set(os)
        return block()
    } finally {
        detectedOperatingSystemOverrideForTesting.set(old)
    }
}

/** Whichever [OperatingSystem] we are executing on, based on the "os.name" property, or [OperatingSystem.UNKNOWN]. */
val detectedOperatingSystem: OperatingSystem by lazy {
    val name = System.getProperty("os.name").toLowerCase()
    when {
        name.contains("win") -> OperatingSystem.WIN
        name.contains("mac") -> OperatingSystem.MAC
        name.contains("linux") -> OperatingSystem.LINUX
        else -> OperatingSystem.UNKNOWN
    }
}

/**
 * Returns whichever [OperatingSystem] we are executing on, as determined by the "os.name" property, or whatever OS was set in a
 * [withOverriddenOperatingSystem] block.
 */
val currentOperatingSystem: OperatingSystem
    get() {
        return detectedOperatingSystemOverrideForTesting.get() ?: detectedOperatingSystem
    }

/** Walks the [Throwable.cause] chain to the root. */
val Throwable.rootCause: Throwable
    get() {
        var t: Throwable = this
        while (t.cause != null) t = t.cause!!
        return t
    }

fun Throwable.asString(): String {
    val sw = StringWriter()
    printStackTrace(PrintWriter(sw))
    return sw.toString()
}

/** Uses [System.nanoTime] to measure elapsed time and exposes it in seconds to 1/10th of a second precision. */
class Stopwatch {
    private val start = System.nanoTime()
    val elapsedInSec: Double get() = (System.nanoTime() - start) / 100000000 / 10.0
}

/** Runs the provided block on the JavaFX main thread, an alias for Platform.runLater */
fun fx(body: () -> Unit): Unit = Platform.runLater(body)

/** Returns the given Maven coordinates in reversed form. That is, components of the groupId are returned in reversed order. */
fun reversedCoordinates(coordinates: String): String {
    val parts = coordinates.split(' ')

    val firstPart = parts[0]
    val groupId = firstPart.substringBefore(':')
    val reversedGroupId = groupId.split('.').asReversed().joinToString(".")
    val afterGroupId = firstPart.substringAfter(':', "")
    val reversedFirstPart = if (afterGroupId.isEmpty()) reversedGroupId else "$reversedGroupId:$afterGroupId"

    val afterFirstPart = parts.asSequence().drop(1).joinToString(" ")

    return if (afterFirstPart.isEmpty()) reversedFirstPart else "$reversedFirstPart $afterFirstPart"
}

/**
 * Returns an iterator over each [JarEntry]. Each time the iterator is advanced the stream can be read to access the
 * underlying entry bytes.
 */
val JarInputStream.entriesIterator: Iterator<JarEntry>
    get() = buildIterator {
        var cursor: JarEntry? = nextJarEntry
        while (cursor != null) {
            if (cursor.realName.contains("..") || cursor.realName.startsWith('/'))
                throw IllegalArgumentException("File path is absolute or contains relative movement: ${cursor.realName}")
            @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")  // Kotlin bug
            yield(cursor!!)
            cursor = nextJarEntry
        }
    }

/**
 * Returns a [JarInputStream] pointed at the given JAR file.
 *
 * @throws NoSuchFileException if the given path does not exist.
 */
fun Path.readAsJar(): JarInputStream = JarInputStream(Files.newInputStream(this).buffered())

/**
 * Returns a file:// URL as a Path, with proper handling of Windows schemes.
 */
fun URL.toPath(): Path {
    val uri = toURI()
    require(uri.scheme == "file") { "${uri.scheme} is not file://" }
    return Paths.get(uri)
}

/**
 * Indicates an HTTP request went wrong. Used because OkHttp doesn't provide its own error codes.
 *
 * @property code HTTP status code if the server responded at the HTTP level, or missing if something went wrong before we could get a code.
 */
class HTTPRequestException(private val code: Int?, message: String, url: HttpUrl, cause: Exception? = null) : Exception("Failed to fetch $url: $code $message", cause)

/**
 * Stashes the given parameters in the properties of the [Artifact].
 */
fun Artifact.withNameAndDescription(name: String, description: String?): Artifact {
    val props: MutableMap<String, String?> = properties.toMutableMap()
    props["model.name"] = name
    props["model.description"] = description
    return setProperties(props)
}

/**
 * If instantiated, allows a block of code to be protected in a thread safe manner such that it can only be entered
 * once. Any other attempt to enter the block will throw [IllegalStateException]. Instances can be used as functions:
 *
 * val once = Once()
 *
 * once {
 *    .. can't re-enter here ..
 * }
 */
class Once {
    private var consumed: Boolean = false

    @Synchronized fun consume() {
        check(!consumed) { "Operation can only be performed once" }
        consumed = true
    }

    inline operator fun <T> invoke(block: () -> T): T {
        consume()
        return block()
    }
}

/**
 * An efficient stream searching class based on the Knuth-Morris-Pratt algorithm. Based on code from Twitter's
 * Elephant-Bird library.
 */
class StreamSearcher(val pattern: ByteArray) {
    private val borders = IntArray(pattern.size + 1)

    init {
        var i = 0
        var j = -1
        borders[i] = j
        while (i < pattern.size) {
            while (j >= 0 && pattern[i] != pattern[j])
                j = borders[j]
            borders[++i] = ++j
        }
    }

    /**
     * Searches for the next occurrence of the pattern in the stream, starting from the current stream position. Note
     * that the position of the stream is changed. If a match is found, the stream points to the end of the match -- i.e. the
     * byte AFTER the pattern. Else, the stream is entirely consumed. The latter is because InputStream semantics make it difficult to have
     * another reasonable default, i.e. leave the stream unchanged.
     *
     * @return bytes consumed if found, -1 otherwise.
     * @throws IOException
     */
    fun search(stream: InputStream): Long {
        var bytesRead: Long = 0

        var b: Int
        var j = 0

        while (true) {
            b = stream.read()
            if (b == -1) break

            bytesRead++

            while (j >= 0 && b.toByte() != pattern[j])
                j = borders[j]

            // Move to the next character in the pattern.
            ++j

            // If we've matched up to the full pattern length, we found it.
            if (j == pattern.size)
                return bytesRead
        }

        return -1
    }
}