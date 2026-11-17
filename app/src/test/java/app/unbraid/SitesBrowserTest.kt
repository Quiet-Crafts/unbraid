package app.unbraid

import app.unbraid.data.Sites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.IDN
import java.util.zip.GZIPInputStream

/**
 * International names against a browser: the hosts were recorded from the WHATWG URL parser (Node 26, ICU 78, Unicode
 * 17), for every character there is and for seeded random mixes. Whatever is saved is the host the browser opens;
 * refusing is allowed, another host never.
 */
class SitesBrowserTest {
    private fun lines(file: String) = GZIPInputStream(javaClass.classLoader!!.getResourceAsStream(file)!!).bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    /** Every character after ASCII, inside "a<c>x.com": kept as it is, opened as another host, or refused by the browser. */
    @Test fun everyCharacterSavesAsTheBrowserOpensIt() {
        val wrong = mutableListOf<String>()
        var saved = 0
        fun check(c: Int, ok: (String) -> Boolean) {
            val name = Sites.clean("a" + String(Character.toChars(c)) + "x.com") ?: return
            saved++
            if (!ok(name)) wrong += "U+%04X saved as %s".format(c, name)
        }
        for (line in lines("idn_every_character.txt.gz")) {
            val p = line.split(' ')
            when (p[0]) {
                // Kept: the saved name must read back as exactly that character.
                "K" -> for (c in p[1].toInt(16)..p[2].toInt(16)) if (c !in 0xd800..0xdfff) check(c) { IDN.toUnicode(it) == "a" + String(Character.toChars(c)) + "x.com" }
                "F" -> for (c in p[1].toInt(16)..p[2].toInt(16)) if (c !in 0xd800..0xdfff) check(c) { false }
                "M" -> check(p[1].toInt(16)) { it == p[2] }
            }
        }
        assertEquals(emptyList<String>(), wrong.take(40))
        assertTrue("only $saved saved", saved > 80_000) // refusing everything would pass the rest
    }

    /** Names mixing scripts, directions, marks, digits of three kinds, mapped letters, invisible characters and dots. */
    @Test fun mixedNamesSaveAsTheBrowserOpensThem() {
        val escape = Regex("""\\u\{([0-9a-f]+)}""")
        val wrong = mutableListOf<String>()
        var saved = 0
        for (line in lines("idn_mixes.txt.gz")) {
            val (text, host) = line.split('\t')
            val raw = escape.replace(text) { String(Character.toChars(it.groupValues[1].toInt(16))) }
            val name = Sites.clean(raw) ?: continue
            saved++
            if (host == "!" || name != host.trim('.').let { h -> generateSequence(h) { it.drop(4).takeIf { d -> it.startsWith("www.") && '.' in d } }.last() }) wrong += "$text saved as $name, browser $host"
        }
        assertEquals(emptyList<String>(), wrong.take(40))
        assertTrue("only $saved saved", saved > 600)
    }
}
