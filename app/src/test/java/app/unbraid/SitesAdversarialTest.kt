package app.unbraid

import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.Setup
import app.unbraid.data.Sites
import app.unbraid.data.VERSION
import app.unbraid.ui.shortName
import app.unbraid.ui.shownSites
import app.unbraid.ui.siteError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Hostile and odd pasted addresses, compared with the host a browser opens for the same text. */
class SitesAdversarialTest {
    /** Each pasted text with the host a browser opens for it (the WHATWG URL parser, as run in Node 26; null = the browser refuses it). */
    private val browser = listOf(
        "https://example.com\\@evil.com" to "example.com",
        "example.com\\@evil.com" to "example.com",
        "https://example.com\\x@evil.com/" to "example.com",
        "https://user:p@ss@example.com/" to "example.com",
        "https://example.com?@evil.com" to "example.com",
        "https://example.com#@evil.com" to "example.com",
        "https://example.com/@evil.com" to "example.com",
        "https://evil.com@example.com" to "example.com",
        "https://evil.com:pw@example.com:8443/x" to "example.com",
        "example.com:443" to "example.com",
        "Example.com:" to "example.com",
        "EXAMPLE.COM" to "example.com",
        "  example.com  " to null,
        "example.com\u000a" to "example.com",
        "exa\u0009mple.com" to "example.com",
        "exa\u000ample.com" to "example.com",
        "ex ample.com" to null,
        "example.com." to "example.com.",
        "example.com.." to "example.com..",
        ".example.com" to ".example.com",
        "http://[::1]:80/" to "[::1]",
        "[2001:db8::1]" to "[2001:db8::1]",
        "2001:db8::1" to null,
        "::1" to null,
        "stra\u00dfe.de" to "xn--strae-oqa.de",
        "fa\u00df.de" to "xn--fa-hia.de",
        "\u03a3\u0391\u03a3.gr" to "xn--mxa9ab.gr",
        "\u1f48\u0394\u03a5\u03a3\u03a3\u0395\u038e\u03a3.gr" to "xn--pxac5babi3d8526a.gr",
        "b\u00fccher.de" to "xn--bcher-kva.de",
        "B\u00dcCHER.de" to "xn--bcher-kva.de",
        "XN--BCHER-KVA.DE" to "xn--bcher-kva.de",
        "xn--mw767205604493046e.com" to "xn--mw767205604493046e.com",
        "xn--" to "xn--",
        "xn--a.com" to "xn--a.com",
        "xn--zz-.com" to "xn--zz-.com",
        "\ud83d\ude00.com" to "xn--e28h.com",
        "a\u200bb.com" to "ab.com",
        "a\u200db.com" to null,
        "a\u200cb.com" to null,
        "example\u3002com" to "example.com",
        "example\uff0ecom" to "example.com",
        "example\uff61com" to "example.com",
        "\uff45\uff58\uff41\uff4d\uff50\uff4c\uff45.\uff43\uff4f\uff4d" to "example.com",
        "0x7f.1" to "127.0.0.1",
        "2130706433" to "127.0.0.1",
        "017700000001" to "127.0.0.1",
        "example.0x1f" to null,
        "example.0X1F" to null,
        "example.\uff11\uff12\uff13" to null,
        "1.2.3.4." to "1.2.3.4",
        "192.168.1.10" to "192.168.1.10",
        "10.1" to "10.0.0.1",
        "localhost" to "localhost",
        "nas" to "nas",
        "co.uk" to "co.uk",
        "github.io" to "github.io",
        "www.co.uk" to "www.co.uk",
        "ex%61mple.com" to "example.com",
        "ex%2Ecom" to "ex.com",
        "%65xample.com" to "example.com",
        "_dmarc.example.com" to "_dmarc.example.com",
        "a_b.com" to "a_b.com",
        "*.example.com" to "*.example.com",
        "-bad.com" to "-bad.com",
        "bad-.com" to "bad-.com",
        "a--b.com" to "a--b.com",
        "example..com" to "example..com",
        "https://www.www.example.com" to "www.www.example.com",
        "ftp://example.com" to "example.com",
        "mailto:a@example.com" to "example.com",
        "//example.com" to "example.com",
        "https:example.com" to null,
        "https:/example.com" to "example.com",
        "https:\\\\example.com\\path" to "example.com",
        "javascript:alert(1)" to null,
        "example.com/a://b@evil.com" to "example.com",
        "http://a.com/b/@c.com" to "a.com",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com" to "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.com",
        "\u0130stanbul.tr" to "xn--istanbul-o0e.tr",
        "\u01c5.com" to "xn--d-toa.com",
        "\ufb01.com" to "fi.com",
        "\u216b.com" to "xii.com",
        "e\u0301.com" to "xn--9ca.com",
        "a\u202eb.com" to null,
        "\ufeffexample.com" to "example.com",
        "a\u0000b.com" to null,
        "example.com%00" to null,
        "example.com:abc" to null,
        "example.com:99999" to null,
        "http://example.com:80:80" to null,
        "example.com\\evil.com" to "example.com",
        "@example.com" to "example.com",
        "https://@example.com" to "example.com",
        "https://example.com@" to null,
        "http://:@example.com" to "example.com",
        "https://xn--bcher-kva.de." to "xn--bcher-kva.de.",
        "B\u00fccher.DE." to "xn--bcher-kva.de.",
        "ex ample" to null,
        "\u00a0example.com" to null,
    )

    /** What a browser opens, as a rule keeps it: no trailing dot, no leading "www.". */
    private fun kept(host: String): String { var n = host.trim('.'); while (n.startsWith("www.") && '.' in n.drop(4)) n = n.drop(4); return n }

    /** Whatever is saved is the website the browser opens. Refusing is always allowed, saving another website never. */
    @Test fun neverAnotherWebsiteThanTheBrowserOpens() {
        val wrong = browser.mapNotNull { (raw, host) ->
            val saved = Sites.clean(raw) ?: return@mapNotNull null
            // Kept on purpose: an address bar trims spaces, and a wrong port only says the name was typed with a mistake.
            if (host == null && (raw.trim() != raw || raw.contains(":abc") || raw.contains(":99999"))) return@mapNotNull null
            if (host == null || saved != kept(host)) "${raw.take(40)} saved as $saved, browser ${host ?: "refuses"}" else null
        }
        assertEquals(emptyList<String>(), wrong)
    }

    /** What must be saved, so the property above is not met by refusing everything. */
    @Test fun theUsualPastesAreSaved() {
        for ((raw, want) in listOf(
            "https://example.com\\@evil.com" to "example.com", "example.com\\@evil.com" to "example.com", "https://example.com\\x@evil.com/" to "example.com",
            "https:\\\\example.com\\path" to "example.com", "example.com\\evil.com" to "example.com", "https://user:p@ss@example.com/" to "example.com",
            "https://evil.com@example.com" to "example.com", "https://evil.com:pw@example.com:8443/x" to "example.com", "http://:@example.com" to "example.com",
            "https://example.com?@evil.com" to "example.com", "https://example.com#@evil.com" to "example.com", "https://example.com/@evil.com" to "example.com",
            "example.com/a://b@evil.com" to "example.com", "http://a.com/b/@c.com" to "a.com", "@example.com" to "example.com",
            "example.com:443" to "example.com", "Example.com:" to "example.com", "EXAMPLE.COM" to "example.com", "  example.com  " to "example.com", "example.com\n" to "example.com",
            "exa\tmple.com" to "example.com", "exa\nmple.com" to "example.com", "example.com." to "example.com", "example.com.." to "example.com", ".example.com" to "example.com",
            "ftp://example.com" to "example.com", "https://www.www.example.com" to "example.com", "https://xn--bcher-kva.de." to "xn--bcher-kva.de",
            "bücher.de" to "xn--bcher-kva.de", "BÜCHER.de" to "xn--bcher-kva.de", "XN--BCHER-KVA.DE" to "xn--bcher-kva.de", "Bücher.DE." to "xn--bcher-kva.de",
            "a\u200bb.com" to "ab.com", "example\u3002com" to "example.com", "example\uff0ecom" to "example.com", "example\uff61com" to "example.com",
            "ｅｘａｍｐｌｅ.ｃｏｍ" to "example.com", "ΣΑΣ.gr" to "xn--mxa9ab.gr", "İstanbul.tr" to "xn--istanbul-o0e.tr", "ﬁ.com" to "fi.com", "Ⅻ.com" to "xii.com",
            "xn--mw767205604493046e.com" to "xn--mw767205604493046e.com", "a".repeat(63) + ".com" to "a".repeat(63) + ".com",
        )) assertEquals(raw, want, Sites.clean(raw))
        // The longest name there is: 253 characters.
        val longest = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
        assertEquals(253, longest.length); assertEquals(longest, Sites.clean(longest)); assertNull(Sites.clean(longest + "e"))
    }

    /** Each refusal says why, in its own sentence, and what it refuses is not saved by [Sites.clean] either unless a saved setup may hold it. */
    @Test fun everyRefusalHasItsOwnReason() {
        val address = "An IP address can't have a rule. Add the website's name"
        val notName = "That isn't a website address. Try one like example.com"
        val letters = "unbraid can't spell this name's letters the way browsers do. Use the form that starts with xn-- instead"
        for ((raw, why) in listOf(
            "192.168.1.10" to address, "10.1" to address, "0x7f.1" to address, "2130706433" to address, "example.0x1f" to address, "1.2.3.4." to address,
            "http://[::1]:80/" to address, "[2001:db8::1]" to address, "::1" to address, "2001:db8::1" to address,
            "localhost" to "localhost is this phone itself, so unbraid can't give it a rule", "nas" to "Add the whole name, such as nas.com",
            "com" to "Add the whole name, such as com.com",
            "a.localhost" to "a.localhost is this phone itself, so unbraid can't give it a rule",
            "1.0.0.10.in-addr.arpa" to "1.0.0.10.in-addr.arpa is a lookup of an address, not a website, so unbraid can't give it a rule",
            "ts.net" to "ts.net is shared by many websites. Add one website under it instead", "k12.ca.us" to "k12.ca.us is shared by many websites. Add one website under it instead",
            "x.sch.uk" to "x.sch.uk is shared by many websites. Add one website under it instead", "com.jm" to "com.jm is shared by many websites. Add one website under it instead",
            "a".repeat(Sites.PASTE + 1) to "That is too long for an address. Paste the link alone",
            "co.uk" to "co.uk is shared by many websites. Add one website under it instead", "www.co.uk" to "co.uk is shared by many websites. Add one website under it instead",
            "github.io" to "github.io is shared by many websites. Add one website under it instead", "CO.UK." to "co.uk is shared by many websites. Add one website under it instead",
            "😀.com" to letters, "a\u202eb.com" to letters, "ex%61mple.com" to notName, "ex%2Ecom" to notName, "_dmarc.example.com" to notName, "a_b.com" to notName, "*.example.com" to notName, "-bad.com" to notName, "example..com" to notName,
            "ex ample.com" to notName, "a".repeat(64) + ".com" to notName, "a\u0000b.com" to notName, "https://example.com@" to notName, "//example.com" to notName, "" to notName,
        )) assertEquals(raw, why, Sites.refusal(raw))
        // ß, a Greek final sigma and the joiners are written one way by the older rule and another by browsers: refused, never saved as another site.
        for (raw in listOf("straße.de", "faß.de", "ας.gr", "a\u200db.com", "a\u200cb.com")) {
            assertEquals(raw, letters, Sites.refusal(raw)); assertNull(raw, Sites.clean(raw))
        }
        // Names a rule can work for in some setup are taken: local and tailnet names (marked "Not applied" while a lookup
        // rule answers them first), someone's Funnel site, a company's internal names on its VPN. And registrable names
        // that only look like a shared ending.
        for (ok in listOf("printer.lan", "myhost.local", "router.home.arpa", "x.fritz.box", "a.internal", "nas.tail1234.ts.net", "box.corp.example", "bbc.co.uk", "me.github.io",
            "go.co", "me.de", "www.ck", "city.kobe.jp", "school.k12.ca.us", "lan.example", "arpa.example", "ts.net.example", "localbank.com", "notlocal.com", "internal.example.org")) assertNull(ok, Sites.refusal(ok))
    }

    /**
     * The shipped endings are exactly the Public Suffix List's rules of two labels or more, both sections, nothing left out
     * and nothing added, under the list's own notice; and its "*." and "!" rules work: a rule for a level of shared
     * endings ("kawasaki.jp") or for one of them is refused, a name the list takes back out ("city.kawasaki.jp") is not.
     */
    @Test fun sharedEndingsAreTheWholeList() {
        val psl = java.util.zip.GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("public_suffix_list.dat.gz")!!).bufferedReader().readText()
        val rules = psl.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && '.' in it }
            .map { r -> Regex("^(\\*\\.|!)?(.*)$").find(r)!!.groupValues.let { it[1] + java.net.IDN.toASCII(it[2], java.net.IDN.ALLOW_UNASSIGNED).lowercase() } }.toSet()
        val text = javaClass.getResource("/shared_suffixes.txt")!!.readText()
        assertEquals(rules, text.lines().filter { it.isNotBlank() && !it.startsWith("//") }.toSet())
        assertTrue(text.startsWith(psl.lines().take(3).joinToString("\n"))) // the list's MPL-2.0 notice, word for word
        assertTrue(Regex("VERSION: ${Regex("VERSION: (\\S+)").find(psl)!!.groupValues[1]}").containsMatchIn(text))
        for (shared in listOf("catholic.edu.au", "onrender.com", "kawasaki.jp", "x.kawasaki.jp", "sch.uk", "x.sch.uk", "nom.br", "x.nom.br", "x.ck", "github.io", "ts.net", "co.uk", "k12.ca.us"))
            assertEquals(shared, "$shared is shared by many websites. Add one website under it instead", Sites.refusal(shared))
        for (own in listOf("city.kawasaki.jp", "www.ck", "y.x.kawasaki.jp", "y.x.sch.uk", "school.catholic.edu.au", "me.onrender.com", "kawasaki-city.jp")) assertNull(own, Sites.refusal(own))
    }

    /** A paste is read whole or refused whole: a long one is never cut into another address. */
    @Test fun aLongPasteIsNeverCut() {
        val long = "https://" + "u".repeat(280) + "@example.com.au"
        assertEquals("example.com.au", Sites.clean(long))
        val longer = "https://" + "u".repeat(Sites.PASTE) + "@example.com.au"
        assertNull(Sites.clean(longer)); assertNull(Sites.clean(longer.take(Sites.PASTE + 1))); assertNull(Sites.clean(longer.take(Sites.PASTE)))
        assertEquals("That is too long for an address. Paste the link alone", siteError(Setup(), longer.take(Sites.PASTE + 1)))
    }

    /** International names are shown in their own letters when that reads back as the saved name, and in ASCII otherwise; never a crash. */
    @Test fun anInternationalNameIsShownReadably() {
        assertEquals("bücher.de", Sites.shown("xn--bcher-kva.de")); assertEquals("münchen.de", Sites.shown("xn--mnchen-3ya.de")); assertEquals("xn--e28h.com", Sites.shown("xn--e28h.com"))
        assertEquals("example.com", Sites.shown("example.com"))
        // ß does not read back (the older rule writes it as "ss"), so the ASCII form stays.
        assertEquals("xn--strae-oqa.de", Sites.shown("xn--strae-oqa.de"))
        // Labels that decode to nothing, to nonsense or past what a name holds stay as they are.
        for (bad in listOf("xn--", "xn--.com", "xn--a.com", "xn--zz-.com", "xn--9999999999.com", "xn--mw767205604493046e.com", "xn--a-.b", "xn----.com", "xn--" + "9".repeat(80) + ".com")) {
            val shown = Sites.shown(bad)
            assertTrue(bad, shown == bad || Sites.clean(shown) == Sites.clean(bad))
        }
        // As browsers show them: a label that mixes scripts, or is made only of letters that look like Latin ones, stays in
        // its ASCII form, so "аpple" with a Cyrillic а never passes for apple. One script, or Latin with Chinese, Japanese
        // or Korean characters, reads in its own letters.
        for (spoof in listOf("xn--pple-43d.example", "xn--l-7sba6dbr.com", "xn--80ak6aa92e.com", "xn--paypl-7ve.com", "xn--vxaj.com")) assertEquals(spoof, Sites.shown(spoof))
        assertEquals("пример.рф", Sites.shown("xn--e1afmkfd.xn--p1ai")); assertEquals("ελληνικά.gr", Sites.shown("xn--hxargifdar.gr"))
        assertEquals("日本語テキストabc.jp", Sites.shown("xn--abc-rj4b9bzdq6889hdocu19k.jp")); assertEquals("한국abc.kr", Sites.shown("xn--abc-lt8lk11n.kr"))
        // Search finds such a name by its own letters and by the Latin ones they look like.
        val spoofs = Setup(routes = listOf(Route("it", Location("nord", 106, "IT", "Italy"))), defaultRoute = DIRECT, sites = mapOf("xn--pple-43d.example" to "it", "apple.com" to "it"), version = VERSION)
        assertEquals(listOf("apple.com", "xn--pple-43d.example"), shownSites(spoofs, "apple", null)); assertEquals(listOf("xn--pple-43d.example"), shownSites(spoofs, "аpple", null))
        // The list searches both forms, so a typed "bücher" finds it.
        val s = Setup(routes = listOf(Route("it", Location("nord", 106, "IT", "Italy"))), defaultRoute = DIRECT, sites = mapOf("xn--bcher-kva.de" to "it", "example.com" to "it"), version = VERSION)
        assertEquals(listOf("xn--bcher-kva.de"), shownSites(s, "bücher", null)); assertEquals(listOf("xn--bcher-kva.de"), shownSites(s, "xn--bcher", null))
        assertEquals(listOf("example.com", "xn--bcher-kva.de"), shownSites(s, "", null).sorted())
    }

    /** A long name is cut in the middle so both ends show, and a short one is left alone. */
    @Test fun aLongNameKeepsBothEnds() {
        val longest = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(59) + ".de"
        val short = shortName(longest)
        assertEquals(53, short.length); assertTrue(short.startsWith("a".repeat(26)) && short.endsWith("d.de")); assertTrue("…" in short)
        assertEquals("example.com", shortName("example.com")); assertEquals("x".repeat(56), shortName("x".repeat(56)))
    }

    /** The sheet's feedback for the same texts: the reason, or the duplicate and limit messages after it. */
    @Test fun theSheetSaysWhy() {
        val s = Setup(routes = listOf(Route("it", Location("nord", 106, "IT", "Italy"))), defaultRoute = DIRECT, sites = mapOf("example.com" to "it"), version = VERSION)
        assertNull(siteError(s, "")); assertNull(siteError(s, "   ")); assertNull(siteError(s, "rai.it"))
        assertEquals("An IP address can't have a rule. Add the website's name", siteError(s, "10.0.0.1"))
        assertEquals("a.localhost is this phone itself, so unbraid can't give it a rule", siteError(s, "https://a.localhost/x")); assertNull(siteError(s, "https://nas.local/x"))
        assertEquals("This website is already on the list", siteError(s, "https://www.example.com\\@evil.com"))
        assertTrue(siteError(s, "straße.de")!!.startsWith("unbraid can't spell"))
    }

    /** Seeded random mixes of odd pieces: whatever is saved is stable, reads back, and agrees with the refusal; nothing throws. */
    @Test fun generatedHostileAddresses() {
        val pieces = listOf("example", "WWW", "www", "Ab", "a", "z9", "0x7f", "0x", "0X1F", "0177", "2130706433", "1", "123", "a-b", "-ab", "ab-", "a--b", "a_b", "☃", "bücher", "BÜCHER",
            "xn--bcher-kva", "XN--BCHER-KVA", "münchen", "a\u200bb", "a\u200db", "a\u202eb", "\ufeffa", "a\u0000b", "a b", "a\tb", "%61", "ex%2Ecom", "😀", "ß", "İstanbul", "ǅ", "ｅｘａｍｐｌｅ",
            "１２３", "localhost", "local", "lan", "com", "co", "uk", "ﬁ", "a\\b", "\"", "*", "Ⅻ", "e\u0301", "a".repeat(63), "b".repeat(64), "ü".repeat(30), "xn--", "xn--a", "xn--zz-", "xn--9999999999")
        val dots = listOf(".", ".", ".", "\u3002", "\uff0e", "\uff61", "..", "")
        val before = listOf("", "", "", "https://", "HTTP://", "ftp://", "http://user:pass@", "http://u@v@", "*.", ".", " ", "\u00a0", "\t", "//", "www.", "[", "mailto:", "a://b@", "https:\\\\")
        val after = listOf("", "", "", ".", "..", ":443", ":443/path?q=x#y", "/path", "?x=@evil.com", "#x", "\\evil.com", ":", ":abc", " ", "\n", "\u3002", "/@evil.com", "@evil.com", "]:80", "%00")
        val rng = Random(0x10CA)
        val bad = mutableListOf<String>()
        repeat(40_000) {
            val raw = before.random(rng) + (0 until rng.nextInt(1, 6)).joinToString("") { (if (it > 0) dots.random(rng) else "") + pieces.random(rng) } + after.random(rng)
            val name = runCatching { Sites.clean(raw) }.getOrElse { bad += "clean threw for ${raw.debugText()}"; return@repeat }
            val why = runCatching { Sites.refusal(raw) }.getOrElse { bad += "refusal threw for ${raw.debugText()}"; return@repeat }
            if (name == null && why == null) bad += "no reason for ${raw.debugText()}"
            if (name != null) {
                if (Sites.clean(name) != name) bad += "${raw.debugText()} saved as unstable $name"
                if (name.length > 253 || name.split('.').size < 2 || name.startsWith("www.") && name.drop(4).contains('.')) bad += "${raw.debugText()} saved as odd $name"
                val shown = Sites.shown(name)
                if (shown != name && Sites.clean(shown) != name) bad += "$name shown as $shown, which saves as ${Sites.clean(shown)}"
                if (why == null && Sites.refusal(name) != null) bad += "${raw.debugText()} is taken but its saved form $name is refused"
            }
        }
        assertEquals(emptyList<String>(), bad.take(10))
    }

    /**
     * Registered-style names in many scripts are taken in both forms, written as browsers write them; the signs IDNA lets
     * stand only beside certain letters are taken there (the Japanese middle dot among kana or kanji, the Catalan one
     * between two l's, the Greek numeral sign, Hebrew gershayim) and refused elsewhere.
     */
    @Test fun namesInEveryScriptAreTakenAsBrowsersWriteThem() {
        for ((raw, ascii) in listOf( // the ASCII forms are a browser's (WHATWG URL parser)
            "カタカナ・テスト.jp" to "xn--lcka6bwa0bnl60b.jp", "ひらがな・カタカナ.jp" to "xn--v8j0cwa6gzha3lrdrv.jp", "漢字・かな.jp" to "xn--u8j2c8vg31l3xu.jp",
            "コーヒー.jp" to "xn--tck2c4fb.jp", "日本語.jp" to "xn--wgv71a119e.jp", "東京大学.jp" to "xn--1lq68wkwbj6u.jp",
            "中文.网" to "xn--fiq228c.xn--ur0a", "北京大学.中国" to "xn--1lq90ic7fzpc.xn--fiqs8s",
            "도메인.한국" to "xn--hq1bm8jm9l.xn--3e0b707e", "한국인터넷진흥원.한국" to "xn--3e0bx5e6xzftae3gxzpskhile.xn--3e0b707e",
            "موقع.وزارة-الاتصالات.مصر" to "xn--4gbrim.xn----ymcbaaajlc6dj7bxne2c.xn--wgbh1c", "مثال.السعودية" to "xn--mgbh0fb.xn--mgberp4a5d4ar",
            "דוגמה.ישראל" to "xn--6dbbec0c.xn--4dbrk0ce", "תשפ״ב.co.il" to "xn--5db1bod6c.co.il",
            "उदाहरण.भारत" to "xn--p1b6ci4b4b3a.xn--h2brj9c", "ตัวอย่าง.ไทย" to "xn--72c1a1bt4awk9o.xn--o3cw4h",
            "col·legi.cat" to "xn--collegi-xma.cat", "ελ͵λάδα.gr" to "xn--wva4ipalc0bb.gr", "пример.рф" to "xn--e1afmkfd.xn--p1ai",
        )) {
            assertEquals(raw, ascii, Sites.clean(raw)); assertNull(raw, Sites.refusal(raw))
            assertEquals(ascii, ascii, Sites.clean(ascii)); assertNull(ascii, Sites.refusal(ascii))
            assertEquals(ascii, raw, Sites.shown(ascii))
        }
        // The same signs where IDNA doesn't let them stand: browsers would open these, no registry hands them out.
        for (raw in listOf("a・b.jp", "・.jp", "l·a.cat", "a·l.cat", "·l.cat", "a͵b.gr", "͵.gr", "a״.co.il", "״ב.co.il", "١۱.example")) {
            assertNull(raw, Sites.clean(raw))
            runCatching { java.net.IDN.toASCII(raw).lowercase() }.getOrNull()?.let { assertNull(it, Sites.clean(it)) }
        }
    }

    /**
     * A label in a script whose letters can spell a Latin word is shown in its own letters only under an ending of its
     * countries or its own script, as browsers do; anywhere else it stays in ASCII, and a search still finds it by the
     * Latin word it looks like.
     */
    @Test fun aLabelThatCanSpellALatinWordShowsOnlyAtHome() {
        for (n in listOf("xn--80a7ec7j.example", "xn--80ak6aa92e.com", "xn--80ak6aa92e.example")) assertEquals(n, n, Sites.shown(n)) // ѵіѕа, аррӏе
        assertEquals("ѵіѕа.ru", Sites.shown("xn--80a7ec7j.ru")); assertEquals("ѵіѕа.рф", Sites.shown("xn--80a7ec7j.xn--p1ai"))
        assertEquals("παράδειγμα.ελ", Sites.shown("xn--hxajbheg2az3al.xn--qxam")); assertEquals("xn--hxajbheg2az3al.com", Sites.shown("xn--hxajbheg2az3al.com"))
        assertEquals("մեկ.հայ", Sites.shown("xn--29aun.xn--y9a3aq")); assertEquals("xn--29aun.com", Sites.shown("xn--29aun.com"))
        assertTrue(Sites.readings("xn--80a7ec7j.example").any { it.contains("visa") })
        val s = Setup(routes = listOf(Route("it", Location("nord", 106, "IT", "Italy"))), defaultRoute = DIRECT, sites = mapOf("xn--80a7ec7j.example" to "it"), version = VERSION)
        assertEquals(listOf("xn--80a7ec7j.example"), shownSites(s, "visa", null))
    }

    /** A restored backup's websites are checked for what any version could have saved, so a name an older version kept stays. */
    @Test fun aStoredNameIsWhatAnyVersionSaved() {
        for (ok in listOf("example.com", "x.0xff", "xn--ax-iuk.com", "xn--lcka6bwa0bnl60b.jp", "www.com", "a-b.c-d")) assertTrue(ok, Sites.stored(ok))
        for (bad in listOf("", "com", "Example.com", "example..com", "-a.com", "a.123", "a b.com", "münchen.de", "a".repeat(64) + ".com", ("a".repeat(60) + ".").repeat(5) + "com"))
            assertTrue(bad, !Sites.stored(bad))
        // Everything the reader keeps is a stored name.
        for (raw in listOf("https://www.Example.com/x", "カタカナ・テスト.jp", "col·legi.cat")) assertTrue(raw, Sites.stored(Sites.clean(raw)!!))
    }

    private fun String.debugText() = map { if (it.code in 32..126) it.toString() else "\\u%04x".format(it.code) }.joinToString("")
}
