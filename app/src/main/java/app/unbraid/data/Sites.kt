package app.unbraid.data

/** Websites with a route of their own ([Setup.sites]): how an address is written, and how many a profile holds. */
object Sites {
    const val MAX = 50
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /**
     * The address in [raw] as a rule keeps it, from what people type or paste ("https://www.Example.com/page" →
     * "example.com"): the name alone, lower case, without "www.", international names in their ASCII form. A rule covers
     * the name and every name under it. Null when it names no website: no dot, an IP address, or characters no name has.
     */
    fun clean(raw: String): String? {
        val host = raw.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .substringBefore(':').trim('.')
        val name = runCatching { java.net.IDN.toASCII(host).lowercase() }.getOrNull() ?: return null
        // Every leading "www." goes while a name is left, so what is kept reads back as itself.
        val labels = generateSequence(name) { n -> n.removePrefix("www.").takeIf { it != n && '.' in it } }.last().split('.')
        // A last label of digits is an IP address, or nothing a name ends in.
        return labels.joinToString(".").takeIf { it.length <= 253 && labels.size >= 2 && labels.all(LABEL::matches) && !labels.last().all(Char::isDigit) }
    }

    /** [sites], most specific first (a name under another comes before it), as runs that share a route: route id to its addresses. */
    fun runs(sites: Map<String, String>): List<Pair<String, List<String>>> =
        sites.entries.sortedWith(compareBy({ -it.key.count { c -> c == '.' } }, { it.value }, { it.key }))
            .fold(mutableListOf<Pair<String, MutableList<String>>>()) { runs, (site, id) ->
                if (runs.lastOrNull()?.first == id) runs.last().second += site else runs += id to mutableListOf(site)
                runs
            }
}
