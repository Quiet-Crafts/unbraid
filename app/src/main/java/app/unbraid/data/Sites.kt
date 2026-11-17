package app.unbraid.data

import app.unbraid.vpn.Compiler
import java.lang.Character.UnicodeScript
import java.net.IDN
import java.text.Normalizer

/** Websites with a route of their own ([Setup.sites]): how an address is written, and how many a profile holds. */
object Sites {
    const val MAX = 50
    /** The longest text the Add website sheet reads: longer is refused whole, never cut into another address. */
    const val PASTE = 2000
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:/+")
    private val TWO_MARKS = Regex("\\p{M}\\p{M}")
    private const val NOT_A_NAME = "That isn't a website address. Try one like example.com"
    private const val ADDRESS = "An IP address can't have a rule. Add the website's name"
    private const val LETTERS = "unbraid can't spell this name's letters the way browsers do. Use the form that starts with xn-- instead"

    /** Hosting platforms where everyone gets a name of their own: a rule for the platform's name would cover them all. */
    private val PLATFORMS = setOf("github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "appspot.com", "netlify.app", "vercel.app", "pages.dev", "workers.dev",
        "web.app", "firebaseapp.com", "azurewebsites.net", "cloudfront.net", "s3.amazonaws.com", "ts.net")

    /** Name endings shared by many unrelated websites ("co.uk"), with the list's wildcard and exception rules. */
    private val SHARED by lazy { Sites::class.java.getResourceAsStream("/shared_suffixes.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.toHashSet() + PLATFORMS }

    /** [name] is itself an ending many websites share, so a rule for it would cover them all. */
    private fun shared(name: String) = name in SHARED || "*.${name.substringAfter('.')}" in SHARED && "!$name" !in SHARED

    /**
     * The address in [raw] as a rule keeps it, from what people type or paste ("https://www.Example.com/page" →
     * "example.com"): the name alone, lower case, without "www.", international names in their ASCII form. A rule covers
     * the name and every name under it. Null when it names no website: no dot, an IP address, or characters no name has.
     * What a saved setup may hold; what a new one may be is [refusal]'s to say.
     */
    fun clean(raw: String): String? = read(raw).first

    /** Why [raw] can't be added as a website, in the Add website sheet's words; null when it can. Only names no rule could ever work for. */
    fun refusal(raw: String): String? {
        val (name, why) = read(raw)
        if (name == null) return why
        never(name)?.let { return "$name is $it, so unbraid can't give it a rule" }
        return if (shared(name)) "$name is shared by many websites. Add one website under it instead" else null
    }

    /** What [name] is when no website rule can ever work for it: no connection is ever made to it by name. */
    private fun never(name: String) = when {
        under(name, "localhost") -> "this phone itself"
        under(name, "arpa") && !under(name, "home.arpa") -> "a lookup of an address, not a website"
        else -> null
    }

    /**
     * Why [s]'s rule for [name] doesn't apply now, or null when it does: one of the engine's earlier rules answers the
     * name's lookups itself (the tailnet's while Reach your tailnet is on, the local network's while it bypasses the
     * VPN), so its connections carry no name a website rule could match. The same domains, from the same functions, as
     * the compiler's. A block of such a name still applies: it refuses the lookup before those rules. [reported] is the
     * tailnet domain Tailscale gave, which a Headscale tailnet's own domain needs: without it only what holds whatever
     * Tailscale reports counts (all of ts.net on Tailscale's own server, local names).
     */
    fun overridden(name: String, s: Setup, reported: String? = null): String? = when {
        s.home.tailscale && Compiler.tsDomains(s.home, reported).any { under(name, it) } ->
            if (Compiler.officialTs(s.home)) "Tailscale answers ts.net names while Reach your tailnet is on" else "your tailnet answers this name while Reach your tailnet is on"
        s.prefs.lan && Compiler.LOCAL_NAMES.any { under(name, it) } -> "your network answers this name while Local network bypasses the VPN is on"
        else -> null
    }

    /**
     * The one decision of which websites apply, [s]'s own or a profile's [sites]: a block always, a route or Direct
     * unless [overridden]. The compiler (with the tailnet domain Tailscale [reported]), the routes in use, protection,
     * statistics and every count go by it; the list and the editing go by what is saved. Without [reported] it leaves
     * out no more than the compiler does, so every route the compiler sends a website to has its tunnel.
     */
    fun applied(s: Setup, sites: Map<String, String> = s.sites, reported: String? = null): Map<String, String> =
        sites.filter { (name, id) -> id == BLOCK || overridden(name, s, reported) == null }

    /** Whether [name] is [suffix] or a name under it. */
    fun under(name: String, suffix: String) = name == suffix || name.endsWith(".$suffix")

    /** Invisible characters both the older rule and browsers drop: a pasted name may carry them. */
    private val DROPPED = setOf(0xad, 0x34f, 0x180b, 0x180c, 0x180d, 0x200b, 0x2060, 0xfeff) + (0xfe00..0xfe0f)
    /** Dots the older rule and browsers both read as a dot. */
    private val DOTS = setOf(0x3002, 0xff0e, 0xff61)
    /**
     * Letters the older rule writes differently from browsers: ß, final sigma, the joiners, case pairs and look-alike
     * forms changed since, and fillers and vowels browsers drop. Found by comparing every character with a browser's parser.
     */
    private val DIFFERENT = setOf(0xdf, 0x3c2, 0x4c0, 0x1806, 0x200c, 0x200d, 0x2132, 0x2183, 0x2f868, 0x2f874, 0x2f91f, 0x2f95f, 0x2f9bf) + (0x10a0..0x10c5)
    /** Characters no browser shows (default ignorable): dropped by browsers, kept by the older rule, unless [DROPPED]. */
    private val IGNORABLE = listOf(0x61c..0x61c, 0x115f..0x1160, 0x17b4..0x17b5, 0x180e..0x180f, 0x200e..0x200f, 0x202a..0x202e, 0x2061..0x206f,
        0x3164..0x3164, 0xffa0..0xffa0, 0xfff0..0xfff8, 0x1bca0..0x1bca3, 0x1d173..0x1d17a, 0xe0000..0xe0fff)
    private val LETTERY = setOf(Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER).map { it.toInt() }.toSet()

    /** A letter, mark or digit both rules write the same way. */
    private fun letter(c: Int) = c < 0x80 || Character.getType(c) in LETTERY && c !in DIFFERENT && IGNORABLE.none { c in it }

    /** The name in [raw] as a browser reads a web address, or the reason there is none. */
    private fun read(raw: String): Pair<String?, String> {
        if (raw.length > PASTE) return null to "That is too long for an address. Paste the link alone"
        // Tabs and line breaks anywhere are dropped and a backslash is a slash, so "example.com\@evil.com" opens example.com.
        var host = raw.trim().filter { it !in "\t\n\r" }.replace('\\', '/').replace(SCHEME, "")
        // The host ends where the path, query or fragment starts; only an "@" before that ends a user name.
        host = host.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        // An IPv6 address, bracketed in a link or bare.
        if (host.startsWith("[") || host.count { it == ':' } > 1) return null to ADDRESS
        host = host.substringBefore(':').trim('.')
        // Only letters, marks and digits that the older rule for international names writes as browsers do: any other
        // character could save as another site.
        host.codePoints().filter { !letter(it) && it !in DROPPED && it !in DOTS }.findFirst().takeIf { it.isPresent }?.let { return null to if (Character.isSpaceChar(it.asInt)) NOT_A_NAME else LETTERS }
        // Composed first by today's tables: the older rule's own composing puts some marks in another order. Two marks on
        // one letter not typed in that order are refused: browsers differ in the order they put them.
        if (!Normalizer.isNormalized(host, Normalizer.Form.NFC) && TWO_MARKS.containsMatchIn(Normalizer.normalize(host, Normalizer.Form.NFD))) return null to LETTERS
        var name = runCatching { IDN.toASCII(Normalizer.normalize(host, Normalizer.Form.NFC)).lowercase() }.getOrNull() ?: return null to if (host.all { it.code < 128 }) NOT_A_NAME else LETTERS
        // Every leading "www." goes while a name is left, so what is kept reads back as itself.
        while (name.startsWith("www.") && '.' in name.drop(4)) name = name.drop(4)
        val labels = name.split('.')
        // A last label that reads as a number (digits, or 0x and hex digits) makes it an IP address to a browser.
        val last = labels.last()
        if (last.all { it in '0'..'9' } || (last.startsWith("0x") && last.drop(2).all { it in "0123456789abcdef" })) return null to if (last.isNotEmpty()) ADDRESS else NOT_A_NAME
        if (name.length > 253 || !labels.all(LABEL::matches)) return null to NOT_A_NAME
        if (labels.size < 2) return null to (never(name)?.let { "$name is $it, so unbraid can't give it a rule" } ?: "Add the whole name, such as $name.com")
        // Read back in its own letters, as a browser checks it: letters both rules write alike, in the one form, each
        // label starting with a letter or digit and written in a direction browsers accept. Else a browser opens another
        // name or none.
        if ("xn--" in name) {
            val read = IDN.toUnicode(name)
            if (!read.codePoints().allMatch { letter(it) } || !Normalizer.isNormalized(read, Normalizer.Form.NFC) || IDN.toASCII(read).lowercase() != name || !bidi(read.split('.'))) return null to NOT_A_NAME
        }
        return name to ""
    }

    private val R = setOf(Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC).map { it.toInt() }.toSet()
    private val NEUTRAL = setOf(Character.DIRECTIONALITY_EUROPEAN_NUMBER_SEPARATOR, Character.DIRECTIONALITY_COMMON_NUMBER_SEPARATOR, Character.DIRECTIONALITY_EUROPEAN_NUMBER_TERMINATOR,
        Character.DIRECTIONALITY_OTHER_NEUTRALS, Character.DIRECTIONALITY_BOUNDARY_NEUTRAL, Character.DIRECTIONALITY_NONSPACING_MARK).map { it.toInt() }.toSet()
    private val L = Character.DIRECTIONALITY_LEFT_TO_RIGHT.toInt()
    private val EN = Character.DIRECTIONALITY_EUROPEAN_NUMBER.toInt()
    private val AN = Character.DIRECTIONALITY_ARABIC_NUMBER.toInt()
    private val NSM = Character.DIRECTIONALITY_NONSPACING_MARK.toInt()

    /**
     * Whether browsers accept [labels] (each in its own letters) as one name: none starts with a mark, and where a label
     * is written right to left, every label keeps to the rules for names that mix directions (RFC 5893).
     */
    private fun bidi(labels: List<String>): Boolean {
        if (labels.any { l -> l.codePointAt(0).let { Character.getType(it) == Character.NON_SPACING_MARK.toInt() || Character.getType(it) == Character.COMBINING_SPACING_MARK.toInt() || Character.getType(it) == Character.ENCLOSING_MARK.toInt() } }) return false
        val dirs = labels.map { l -> l.codePoints().toArray().map { Character.getDirectionality(it).toInt() } }
        if (dirs.none { d -> d.any { it in R || it == AN } }) return true
        return dirs.all { d ->
            val end = d.dropLastWhile { it == NSM }.lastOrNull()
            if (d.first() in R) d.all { it in R || it == AN || it == EN || it in NEUTRAL } && (end in R || end == EN || end == AN) && !(EN in d && AN in d)
            else d.first() == L && d.all { it == L || it == EN || it in NEUTRAL } && (end == L || end == EN)
        }
    }

    /** Scripts a label may mix and still be shown in its own letters, as browsers allow: Latin with Chinese and Japanese, Chinese, or Korean characters. */
    private val MIXES = listOf(setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA),
        setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.BOPOMOFO), setOf(UnicodeScript.LATIN, UnicodeScript.HAN, UnicodeScript.HANGUL))
    /** Letters of other scripts that look like Latin ones, with the Latin letter each looks like. */
    private val LOOKALIKE = ("асԁеһіјӏорԛѕԝхуαικνορτυχօսհոց" zip "acdehijlopqswxyaikvoptuxouhng").toMap()

    /** [name]'s labels in their own letters, or null when it has none ("xn--" labels that decode to nothing valid). */
    private fun decoded(name: String) = if ("xn--" !in name) null else runCatching { IDN.toUnicode(name) }.getOrNull()?.takeIf { it != name && clean(it) == name }

    /**
     * [name] as people read it: an international name in its own letters ("xn--mnchen-3ya.de" → "münchen.de"). Only a
     * reading that writes back to [name] itself, and, as browsers show it, only when no label mixes scripts or is made
     * of letters that look like Latin ones ("xn--pple-43d.example" stays, not "аpple.example"): anything else stays in its ASCII form.
     */
    fun shown(name: String): String = decoded(name)?.takeIf { it.split('.').all(::plain) } ?: name

    private fun plain(label: String): Boolean {
        val scripts = label.codePoints().toArray().map(UnicodeScript::of).filter { it != UnicodeScript.COMMON && it != UnicodeScript.INHERITED }.toSet()
        if (scripts.size > 1 && MIXES.none { it.containsAll(scripts) }) return false
        return scripts.singleOrNull() == UnicodeScript.LATIN || scripts.isEmpty() || !label.all { it in LOOKALIKE || it.isDigit() || it == '-' }
    }

    /** What a search of the list matches [name] by: the name, its own letters, and those letters read as the Latin ones they look like. */
    fun readings(name: String): List<String> = listOfNotNull(name, decoded(name), decoded(name)?.map { LOOKALIKE[it] ?: it }?.joinToString(""))

    /** The website among [sites] that [host] belongs to (the name itself or one under it), the most specific one; null for none. */
    fun match(host: String, sites: Collection<String>): String? =
        host.lowercase().trimEnd('.').let { h -> sites.filter { h == it || h.endsWith(".$it") }.maxByOrNull { it.length } }

    /** [sites], most specific first (a name under another comes before it), as runs that share a route: route id to its addresses. */
    fun runs(sites: Map<String, String>): List<Pair<String, List<String>>> =
        sites.entries.sortedWith(compareBy({ -it.key.count { c -> c == '.' } }, { it.value }, { it.key }))
            .fold(mutableListOf<Pair<String, MutableList<String>>>()) { runs, (site, id) ->
                if (runs.lastOrNull()?.first == id) runs.last().second += site else runs += id to mutableListOf(site)
                runs
            }
}
