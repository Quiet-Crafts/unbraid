package app.unbraid.data

import app.unbraid.vpn.Compiler

/** Websites with a route of their own ([Setup.sites]): how an address is written, and how many a profile holds. */
object Sites {
    const val MAX = 50
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:/+")
    private val SHARED = Regex("(co|com|net|org|gov|edu|ac|or|ne|go|ltd|plc|me)\\.[a-z]{2}")
    private const val NOT_A_NAME = "That isn't a website address. Try one like example.com"
    private const val ADDRESS = "An IP address can't have a rule. Add the website's name"
    private const val LETTERS = "unbraid can't spell this name's letters the way browsers do. Use the form that starts with xn-- instead"

    /** Where names on the local network end: answered by the router or the phone, never by a VPN's resolver. */
    private val LOCAL = listOf("local", "lan", "arpa", "fritz.box", "localhost", "internal")

    /** Hosting platforms where everyone gets a name of their own: a rule for the platform's name would cover them all. */
    private val PLATFORMS = setOf("github.io", "gitlab.io", "blogspot.com", "herokuapp.com", "appspot.com", "netlify.app", "vercel.app", "pages.dev", "workers.dev",
        "web.app", "firebaseapp.com", "azurewebsites.net", "cloudfront.net", "s3.amazonaws.com")

    /**
     * The address in [raw] as a rule keeps it, from what people type or paste ("https://www.Example.com/page" →
     * "example.com"): the name alone, lower case, without "www.", international names in their ASCII form. A rule covers
     * the name and every name under it. Null when it names no website: no dot, an IP address, or characters no name has.
     * What a saved setup may hold; what a new one may be is [refusal]'s to say.
     */
    fun clean(raw: String): String? = read(raw).first

    /** Why [raw] can't be added as a website, in the Add website sheet's words; null when it can. [tailnet]: see [ownNetwork]. */
    fun refusal(raw: String, tailnet: String? = null): String? {
        val (name, why) = read(raw)
        if (name == null) return why
        ownNetwork(name, tailnet)?.let { return "$name is $it, so unbraid can't give it a rule" }
        return if (name in PLATFORMS || SHARED.matches(name)) "$name is shared by many websites. Add one website under it instead" else null
    }

    /**
     * What [name] is when a website rule can't work for it, or null for any other name: a name on the own network or in
     * the tailnet is answered by its own resolver, which a website's route would skip, and `.local` names never pass
     * unbraid at all. [tailnet] is the tailnet's own domain, when there is one.
     */
    fun ownNetwork(name: String, tailnet: String? = null): String? = when {
        tailnet != null && under(name, tailnet) -> "a name in your tailnet"
        under(name, "ts.net") -> "a Tailscale name, answered by Tailscale"
        under(name, "localhost") -> "this phone itself"
        under(name, "arpa") && !under(name, "home.arpa") -> "a lookup of an address, not a website"
        LOCAL.any { under(name, it) } -> "a name on your own network"
        else -> null
    }

    /** The tailnet domain [s]'s websites are judged by: the one a Headscale tailnet was allowed, while Tailscale is used. */
    fun tailnet(s: Setup): String? = if (s.tailscale && !Compiler.officialTs(s.home)) Compiler.tsSuffix(s.home.tsSuffixAllowed) else null

    /**
     * The one decision of which of [s]'s websites apply: a route or Direct is left out for a name on the own network or in
     * the tailnet (a setup saved before those were refused may hold one), a block of one stays a block. The compiler, the
     * statistics and the rows go by this.
     */
    fun applied(s: Setup): Map<String, String> = tailnet(s).let { t -> s.sites.filter { (name, id) -> id == BLOCK || ownNetwork(name, t) == null } }

    /** Whether [name] is [suffix] or a name under it. */
    fun under(name: String, suffix: String) = name == suffix || name.endsWith(".$suffix")

    /** The name in [raw] as a browser reads a web address, or the reason there is none. */
    private fun read(raw: String): Pair<String?, String> {
        // Tabs and line breaks anywhere are dropped and a backslash is a slash, so "example.com\@evil.com" opens example.com.
        var host = raw.trim().filter { it !in "\t\n\r" }.replace('\\', '/').replace(SCHEME, "")
        // The host ends where the path, query or fragment starts; only an "@" before that ends a user name.
        host = host.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        // An IPv6 address, bracketed in a link or bare.
        if (host.startsWith("[") || host.count { it == ':' } > 1) return null to ADDRESS
        host = host.substringBefore(':').trim('.')
        // The older rule for international names writes these differently from browsers, so such a name would save as another site.
        if (host.any { it == 'ß' || it == 'ς' || it.code == 0x200c || it.code == 0x200d }) return null to LETTERS
        var name = runCatching { java.net.IDN.toASCII(host).lowercase() }.getOrNull() ?: return null to if (host.all { it.code < 128 }) NOT_A_NAME else LETTERS
        // Every leading "www." goes while a name is left, so what is kept reads back as itself.
        while (name.startsWith("www.") && '.' in name.drop(4)) name = name.drop(4)
        val labels = name.split('.')
        // A last label that reads as a number (digits, or 0x and hex digits) makes it an IP address to a browser.
        val last = labels.last()
        if (last.all { it in '0'..'9' } || (last.startsWith("0x") && last.drop(2).all { it in "0123456789abcdef" })) return null to if (last.isNotEmpty()) ADDRESS else NOT_A_NAME
        if (name.length > 253 || !labels.all(LABEL::matches)) return null to NOT_A_NAME
        if (labels.size < 2) return null to (ownNetwork(name)?.let { "$name is $it, so unbraid can't give it a rule" } ?: "Add the whole name, such as $name.com")
        return name to ""
    }

    /** [name] as people read it: an international name in its own letters ("xn--mnchen-3ya.de" → "münchen.de"). Only a reading that writes back to [name] itself is shown; anything else stays in its ASCII form. */
    fun shown(name: String): String {
        if ("xn--" !in name) return name
        val read = runCatching { java.net.IDN.toUnicode(name) }.getOrNull() ?: return name
        return if (read != name && runCatching { java.net.IDN.toASCII(read).lowercase() }.getOrNull() == name) read else name
    }

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
