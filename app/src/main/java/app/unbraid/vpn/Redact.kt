package app.unbraid.vpn

import app.unbraid.data.AuthException
import kotlinx.serialization.SerializationException
import java.io.IOException

/** A failure whose message is written by Unbraid and safe to show as is. */
open class Fail(message: String) : IOException(message)

/**
 * Engine and library errors can quote the configuration they choked on, private keys included. Nothing from them
 * reaches the log or the screen without passing through here.
 *
 * Every pass is linear in the text (no pattern can recurse or backtrack far), so any input is safe to clean.
 */
object Redact {
    private const val HIDDEN = "[hidden]"
    private const val MAX = 160

    /** OpenVPN inline key material and PEM bodies, up to their end or the end of the text. */
    private val BLOCK = Regex("""(?i)<(tls-auth|tls-crypt|tls-crypt-v2|secret|key|auth-user-pass|pkcs12)>[\s\S]*?(?:</\1>|$)|-----BEGIN [^\n-]{1,60}-----[\s\S]*?(?:-----END [^\n-]{1,60}-----|$)""")

    /** The login part of a URL (`scheme://user:password@`), up to the last `@` of the address. */
    private val USERINFO = Regex("""(?i)(?<![a-z0-9+.-])([a-z][a-z0-9+.-]{0,15}://)[^\s"'<>]{0,512}@""")

    /**
     * A name that a secret follows, after up to three prefix words (`nordlynx_private_key`, `X-Api-Key`), and the
     * separator after it (an optional closing quote, then `:`, `=` or blanks). Group 1 is the name without its prefix.
     */
    private val NAME = Regex(
        """(?i)(?<![a-z0-9_])(?:[a-z0-9]{1,20}[_-]){0,3}?(authorization|cookie|auth-user-pass|http-proxy-user-pass|passphrase|passw(?:or)?d|pwd|""" +
            """(?:private|pre_?shared|auth|api|secret)[_-]?key|apikey|secrets?|psk|[a-z]*token|bearer|basic|uuid|""" +
            """key|pass|pt|user(?:name)?|login|account(?:_?(?:number|id))?|auth|credentials?|session_?id|uid|sig(?:nature)?)""" +
            """(?![a-z0-9])(\\*["']?\s*[:=]\s*|\s+)""",
    )

    /** Names too common in plain text to hide what follows them without a `:` or `=`. */
    private val WEAK = Regex("authorization|cookie|key|pass|pt|user(name)?|login|account.*|auth|credentials?|session_?id|uid|sig(nature)?")

    /** Names whose unquoted value is the rest of the line: headers, OpenVPN login lines, passwords with spaces. */
    private val LINE = Regex("authorization|cookie|auth-user-pass|http-proxy-user-pass|passphrase|passw(or)?d|pwd")

    /** Credential shapes that need no name: tokens, ids, account numbers, hex key rows and key-like runs. */
    private val SHAPES = listOf(
        Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*)?"""),
        Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"""),
        Regex("""tskey-[A-Za-z0-9-]+"""),
        Regex("""\bi-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}-[A-Za-z0-9]{4}\b"""),
        Regex("""\bp\d{7}\b"""),
        Regex("""\b\d{4}(?: ?\d{4}){3}\b"""),
        Regex("""\b[0-9a-fA-F]{32,}\b"""),
        Regex("""[A-Za-z0-9+/_-]{40,}={0,2}"""),
    )

    /** A run of 20+ key characters is a key fragment when it mixes upper case, lower case and digits, or holds + or /. */
    private val FRAGMENT = Regex("""[A-Za-z0-9+/_=-]{20,}""")

    private fun mixed(s: String) = s.any(Char::isDigit) && s.any(Char::isUpperCase) && s.any(Char::isLowerCase)

    private fun fragment(s: String) = mixed(s) || s.any(Char::isDigit) && ('+' in s || '/' in s)

    fun clean(s: String?): String {
        var t = (s ?: "").replace(BLOCK) { "<${it.groupValues[1].ifEmpty { "pem" }}>$HIDDEN" }
        t = t.replace(USERINFO) { it.groupValues[1] + HIDDEN + "@" }
        t = named(t)
        for (r in SHAPES) t = t.replace(r, HIDDEN)
        t = t.replace(FRAGMENT) { if (fragment(it.value)) HIDDEN else it.value }
        return t.replace('\n', ' ').take(MAX)
    }

    /** Replaces the value after each [NAME], scanning it by hand so a long or oddly quoted value costs one pass. */
    private fun named(s: String): String {
        val out = StringBuilder()
        var copied = 0
        var from = 0
        while (from < s.length) {
            val m = NAME.find(s, from) ?: break
            val name = m.groupValues[1].lowercase()
            val spaced = m.groupValues[2].none { it == ':' || it == '=' }
            val start = m.range.last + 1
            val end = valueEnd(s, start, LINE.matches(name))
            val value = s.substring(start, end)
            // After a blank only (plain text), a common name hides only what looks like a credential.
            val hide = value.isNotEmpty() && (!spaced || name.endsWith("-pass") || if (WEAK.matches(name)) mixed(value) else value[0] in "\"'\\" || value.any(Char::isDigit))
            if (hide) {
                out.append(s, copied, start).append(HIDDEN)
                copied = end
                from = end
            } else {
                from = m.range.first + 1
            }
        }
        return if (copied == 0) s else out.append(s, copied, s.length).toString()
    }

    /** Where the value starting at [i] ends: its closing quote (escapes skipped), the end of the line, or the next blank. */
    private fun valueEnd(s: String, i: Int, toLineEnd: Boolean): Int {
        if (i >= s.length) return i
        var j = i
        while (j < s.length && s[j] == '\\') j++
        if (j < s.length && (s[j] == '"' || s[j] == '\'')) {
            // A quoted value, possibly JSON-escaped (\" ... \"): it ends at the same quote sequence.
            val q = s.substring(i, j + 1)
            if (j == i) {
                var k = i + 1
                while (k < s.length && s[k] != s[i]) k += if (s[k] == '\\') 2 else 1
                return minOf(k + 1, s.length)
            }
            val k = s.indexOf(q, j + 1)
            return if (k < 0) s.length else k + q.length
        }
        if (s[i] == '[') return s.indexOf(']', i).let { if (it < 0) s.length else it + 1 }
        if (toLineEnd) return s.indexOf('\n', i).let { if (it < 0) s.length else it }
        while (j < s.length && !s[j].isWhitespace() && s[j] !in ",;&)}]\"'") j++
        return j
    }

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
