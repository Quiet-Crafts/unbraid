package app.unbraid.vpn

import app.unbraid.data.AuthException
import kotlinx.serialization.SerializationException
import java.io.IOException

/** A failure whose message is written by Unbraid and safe to show as is. */
open class Fail(message: String) : IOException(message)

/**
 * Engine and library errors can quote the configuration they choked on, private keys included. Nothing from them
 * reaches the log or the screen without passing through here.
 */
object Redact {
    // The value: a quoted string (spaces and all) or, unquoted, up to the next blank.
    private val SECRET = Regex("""(?i)(private_?key|pre_?shared_?key|auth_?key|password|secret|token)["']?\s*[:=]\s*("(?:\\.|[^"\\])*"?|'(?:\\.|[^'\\])*'?|\S*)""") // quoted values may contain escaped quotes
    private val BLOB = Regex("""[A-Za-z0-9+/_-]{40,}={0,2}""")
    private const val MAX = 160

    fun clean(s: String?): String = (s ?: "").replace(SECRET, "[hidden]").replace(BLOB, "[hidden]").replace('\n', ' ').take(MAX)

    /** What to tell the user: our own messages (cleaned), else a fixed text for the failure class. */
    fun message(e: Throwable, fallback: String): String = when {
        e is AuthException || e is Fail -> clean(e.message).ifBlank { fallback }
        e is SerializationException -> fallback
        e is IllegalArgumentException || e is IllegalStateException -> clean(e.message).ifBlank { fallback }
        e is IOException -> "Couldn't reach the server. Check the connection."
        else -> fallback
    }

    /** One safe line for logcat. */
    fun log(e: Throwable) = "${e.javaClass.simpleName}: ${clean(e.message)}"
}
