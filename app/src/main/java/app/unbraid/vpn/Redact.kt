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
 * Every pass is linear in the text (no pattern can recurse or backtrack far, and no scan reads the same stretch again
 * for each name in it), so any input is safe to clean.
 */
object Redact {
    private const val HIDDEN = "[hidden]"
    private const val MAX = 160
    private const val ESCAPED_MAX = 4096

    /** Where OpenVPN inline key material or a PEM body starts. */
    private val OPEN = Regex("""(?i)<(tls-auth|tls-crypt|tls-crypt-v2|secret|key|auth-user-pass|pkcs12)>|-----BEGIN [^\n-]{1,60}-----""")
    private val PEM_END = Regex("""-----END [^\n-]{1,60}-----""")

    /**
     * The app's own messages name tags ("both <cert> and <key>, or neither", "the <key> block is empty"); these are
     * the exact words that follow a tag there. After anything else an unclosed tag hides the rest of the text.
     */
    private val PROSE = Regex("""(?:, or neither| block\), not a file path| blocks| block is empty or malformed)(?![A-Za-z0-9])""").toPattern()

    /** The login part of a URL (`scheme://user:password@`), up to the last `@` of the address. */
    private val SCHEME = Regex("""(?i)(?<![a-z0-9+.-])[a-z][a-z0-9+.-]{0,15}://""")

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
        Regex("""(?<![A-Za-z0-9_-])eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]*)?"""),
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
        var t = blocks(s ?: "")
        t = userinfo(t)
        t = named(t)
        for (r in SHAPES) t = t.replace(r, HIDDEN)
        t = t.replace(FRAGMENT) { if (fragment(it.value)) HIDDEN else it.value }
        return t.replace('\n', ' ').take(MAX)
    }

    /**
     * Hides OpenVPN inline blocks and PEM bodies, each up to its closing tag (the first after it), or to the end of the
     * text when it has none. Closing tags are looked up once per name and scan position, so any number of tags costs
     * one pass.
     */
    private fun blocks(s: String): String {
        val out = StringBuilder()
        val found = HashMap<String, IntArray>() // closing tag -> [searched from, starts at (-1 none), ends at]
        fun close(name: String, from: Int): IntArray {
            val c = found[name]
            if (c != null && from >= c[0] && (c[1] < 0 || from <= c[1])) return c
            val r = if (name.isEmpty()) PEM_END.find(s, from)?.let { intArrayOf(from, it.range.first, it.range.last + 1) }
                else s.indexOf("</$name>", from, ignoreCase = true).let { if (it < 0) null else intArrayOf(from, it, it + name.length + 3) }
            return (r ?: intArrayOf(from, -1, -1)).also { found[name] = it }
        }
        var i = 0
        while (i < s.length) {
            val m = OPEN.find(s, i) ?: break
            val tag = m.groupValues[1]
            val body = m.range.last + 1
            val end = close(tag.lowercase(), body)[2]
            val loginOrSecret = tag.equals("auth-user-pass", true) || tag.equals("secret", true)
            if (end < 0 && tag.isNotEmpty() && !loginOrSecret && PROSE.matcher(s).region(body, s.length).lookingAt()) {
                out.append(s, i, body) // a tag named in one of the app's own messages
                i = body
                continue
            }
            out.append(s, i, m.range.first).append("<${tag.ifEmpty { "pem" }}>").append(HIDDEN)
            i = if (end < 0) s.length else end
        }
        return if (i == 0) s else out.append(s, i, s.length).toString()
    }

    /**
     * The first index at or after a position where [p] holds, remembered so that the rising positions of one scan
     * cost one pass over the text in all, however many names point into the same long line.
     */
    private class Next(private val s: String, private val p: (Char) -> Boolean) {
        private var from = -1
        private var at = -1
        operator fun invoke(i: Int): Int {
            if (i < from || i > at) { from = i; at = i; while (at < s.length && !p(s[at])) at++ }
            return at
        }
    }

    /**
     * Hides the login part of each URL (`scheme://user:password@`): up to the last `@` within 512 characters of the
     * scheme, before any blank, quote or angle bracket. The `@` positions are read once, in one forward pass.
     */
    private fun userinfo(s: String): String {
        val stop = Next(s) { it.isWhitespace() || it in "\"'<>" }
        val out = StringBuilder()
        var copied = 0
        var from = 0
        var read = 0 // every '@' before this index has been seen; the last one is [last]
        var last = -1
        while (from < s.length) {
            val m = SCHEME.find(s, from) ?: break
            val start = m.range.last + 1
            val limit = minOf(stop(start), start + 513, s.length)
            while (read < limit) { if (s[read] == '@') last = read; read++ }
            if (last >= start) {
                out.append(s, copied, start).append(HIDDEN).append('@')
                copied = last + 1
                from = last + 1
            } else from = start
        }
        return if (copied == 0) s else out.append(s, copied, s.length).toString()
    }

    /** Replaces the value after each [NAME], scanning it by hand so a long or oddly quoted value costs one pass. */
    private fun named(s: String): String {
        val out = StringBuilder()
        val digit = Next(s, Char::isDigit)
        val upper = Next(s, Char::isUpperCase)
        val lower = Next(s, Char::isLowerCase)
        val newline = Next(s) { it == '\n' }
        val bracket = Next(s) { it == ']' }
        var copied = 0
        var from = 0
        while (from < s.length) {
            val m = NAME.find(s, from) ?: break
            val name = m.groupValues[1].lowercase()
            val spaced = m.groupValues[2].none { it == ':' || it == '=' }
            val start = m.range.last + 1
            val end = valueEnd(s, start, LINE.matches(name), newline, bracket)
            val hasDigit = digit(start) < end
            val mixed = hasDigit && upper(start) < end && lower(start) < end
            // After a blank only (plain text), a common name hides only what looks like a credential.
            val hide = end > start && (!spaced || name.endsWith("-pass") || if (WEAK.matches(name)) mixed else s[start] in "\"'\\" || hasDigit)
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
    private fun valueEnd(s: String, i: Int, toLineEnd: Boolean, newline: Next, bracket: Next): Int {
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
            // Looked for in a bounded window: a text of many different escape depths must not be read once per depth.
            // Past it the value runs to the end of the text, which hides more, never less.
            val k = s.substring(j + 1, minOf(s.length, j + 1 + ESCAPED_MAX + q.length)).indexOf(q)
            return if (k < 0) s.length else j + 1 + k + q.length
        }
        if (s[i] == '[') return bracket(i).let { if (it >= s.length) s.length else it + 1 }
        if (toLineEnd) return newline(i)
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
