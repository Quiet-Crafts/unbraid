package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.Backup.Reason
import app.unbraid.data.DIRECT
import app.unbraid.data.Location
import app.unbraid.data.OwnServer
import app.unbraid.data.PREVIOUS
import app.unbraid.data.Route
import app.unbraid.data.Server
import app.unbraid.data.Setup
import app.unbraid.data.VERSION
import app.unbraid.data.WgConf
import app.unbraid.data.restoredFrom
import app.unbraid.ui.passProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

/** Hostile and odd backups, made up from a seed: what is read, what is restored, and how passphrases are judged. */
class BackupAdversarialTest {
    private val wg = WgConf.parse("[Interface]\nPrivateKey = ${"A".repeat(42)}E=\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = ${"B".repeat(42)}E=\nEndpoint = 203.0.113.1:51820\nAllowedIPs = 0.0.0.0/0\n", "Box")
    private val evil = Server("se-got-wg-001", "se-got-wg-001.relays.mullvad.net", "198.51.100.66", "C".repeat(42) + "E=", 3, "Gothenburg", "Sweden")
    private fun reason(f: () -> Unit) = try { f(); fail("accepted"); null } catch (e: Backup.Failed) { e.reason }
    private fun text(s: Setup, app: String = "3.0.2") = Backup.export(s, app, 0).toString(Charsets.UTF_8)
    private fun forKeys(p: String) = passProblem(p, p, keys = true) == null

    @Test fun aPinnedServerKeepsOnlyItsNameAndHost() {
        val pinned = Location("mullvad", countryCode = "SE", country = "Sweden", pinned = evil)
        val here = Setup(routes = listOf(Route("se", pinned), Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "se",
            favorites = listOf(pinned), opts = mapOf("ivpn" to mapOf("list" to "198.51.100.53|198.51.100.54", "protect" to "on")), version = VERSION)
        val c = Backup.read(Backup.export(here, "3.0.2", 0))
        val pin = c.setup.route("se")!!.loc.pinned!!
        assertEquals(Server(evil.name, evil.host, "", "", -1, evil.city, evil.country), pin)
        assertEquals(pin, c.setup.favorites.single().pinned)
        // The block list is a pair of resolver addresses: it starts again from the provider's standard one.
        assertEquals(mapOf("protect" to "on"), c.setup.opts["ivpn"])
        // Keeping the setup in use on the same phone shares the pinned route rather than adding a copy of it.
        val r = here.restoredFrom(c.setup, PREVIOUS, 0)!!
        assertEquals(c.setup.routes.size, r.routes.size)
        assertEquals("se", r.profiles.single { it.name == PREVIOUS }.defaultRoute)
        Backup.valid(r)
    }

    @Test fun aMacBackupIsNamed() {
        val mac = """{"format":1,"platform":"mac","app":"1.4.0","created":0,"keys":true,"mac":{"routes":[],"defaultRoute":"direct"}}"""
        assertEquals(Reason.OtherPlatform, reason { Backup.read(mac.toByteArray()) })
        assertEquals("This backup was made by unbraid for Mac. Moving a setup between Mac and Android isn't supported yet.", Reason.OtherPlatform.text)
        // Not a Mac backup just because a key is called "mac".
        assertEquals(Reason.Invalid, reason { Backup.read("""{"mac":1,"setup":5}""".toByteArray()) })
        for (junk in listOf("[]", "null", "\"mac\"", "{\"mac\":" + "[".repeat(100_000))) assertEquals(junk.take(12), Reason.Invalid, reason { Backup.read(junk.toByteArray()) })
    }

    @Test fun textsFromTheFileAreBoundedAndPlain() {
        val s = Setup(routes = listOf(Route("nl", Location("nord", 153, "NL", "Netherlands"))), defaultRoute = "nl", version = VERSION)
        // The version that made it: a short version string or nothing.
        assertEquals("3.0.2-beta+1", Backup.read(Backup.export(s, "3.0.2-beta+1", 0)).app)
        for (app in listOf("x".repeat(1 shl 20), "3.0‮", "<b>3</b>", "", " 3.0", "3".repeat(33))) assertEquals("", Backup.read(Backup.export(s, app, 0)).app)
        val nl = s.routes[0]
        for (bad in listOf(
            nl.copy(loc = nl.loc.copy(country = "Nether‮lands")), nl.copy(loc = nl.loc.copy(city = "x".repeat(300))), nl.copy(loc = nl.loc.copy(region = "a\nb")),
            nl.copy(loc = nl.loc.copy(country = " ")), nl.copy(loc = nl.loc.copy(pinned = evil.copy(name = "⁦evil"))), nl.copy(loc = nl.loc.copy(group = "g\u0000")),
        )) assertEquals(bad.loc.toString(), Reason.Invalid, reason { Backup.valid(s.copy(routes = listOf(bad))) })
        assertEquals(Reason.Invalid, reason { Backup.valid(s.copy(routes = s.routes + Route("t", tsExit = "100.64.0.1", tsName = "node‮"))) })
        assertEquals(Reason.Invalid, reason { Backup.valid(s.copy(favorites = listOf(Location("nord", country = "x".repeat(257))))) })
        Backup.valid(s.copy(routes = s.routes + Route("t", tsExit = "100.64.0.1", tsName = "Home node"), favorites = listOf(Location("nord", city = "São Paulo"))))
    }

    @Test fun aBackupThatCouldNotBeRestoredIsNeverSaved() {
        val s = Setup(routes = listOf(Route("nl", Location("nord"))), defaultRoute = "nl", apps = mapOf("org.example.a" to "gone"), version = VERSION)
        val e = try { Backup.readBack(Backup.export(s, "3.0.2", 0)); null } catch (e: Backup.Failed) { e }
        assertEquals(Reason.Unrestorable, e!!.reason)
        assertEquals("Nothing was saved, because this backup couldn't be restored: an app or the default route names a route that isn't there", e.message)
        Backup.readBack(Backup.export(s.copy(apps = emptyMap()), "3.0.2", 0))
    }

    @Test fun aRouteWithoutAnOwnServerGetsItsConfigBackOnTheSamePhone() {
        val here = Setup(routes = listOf(Route("w", wg = wg)), defaultRoute = "w", apps = mapOf("org.example.a" to DIRECT), version = VERSION)
        val c = Backup.read(Backup.export(here, "3.0.2", 0, keys = false))
        assertTrue(c.setup.servers.single().keyless)
        val r = here.restoredFrom(c.setup, null, 0, c.keys)!!
        assertTrue(r.servers.none { it.keyless } && r.routes.none { it.keyless })
        assertEquals(wg, r.route("w")!!.wg)
        Backup.valid(r)
        // Keeping the setup in use: the same route, not a second working copy beside it.
        val kept = here.restoredFrom(c.setup, PREVIOUS, 0, c.keys)!!
        assertEquals(1, kept.routes.size)
        assertEquals("w", kept.profiles.single().defaultRoute)
        // Another phone's route of that name with another server is not taken for it.
        val other = here.copy(routes = listOf(Route("w", wg = wg.copy(endpoint = "203.0.113.9:51820"))))
        assertTrue(other.restoredFrom(c.setup, null, 0, c.keys)!!.servers.single().keyless)
        // Nor is one tied to an own server of its own.
        assertTrue(here.copy(routes = listOf(Route("w", wg = wg, own = "x")), servers = listOf(OwnServer("x", wg = wg))).restoredFrom(c.setup, null, 0, c.keys)!!.servers.any { it.keyless })
    }

    @Test fun everySaveNeedsEightCharactersAndKeysNeedAHardOne() {
        assertEquals("", passProblem("", "", false))
        assertEquals("A passphrase needs at least 8 characters.", passProblem("a", "a", false))
        assertEquals("A passphrase needs at least 8 characters.", passProblem("😀😀😀😀😀😀😀", "😀😀😀😀😀😀😀", false))
        assertNull(passProblem("abcdefgh", "abcdefgh", false))
        assertEquals("", passProblem("abcdefgh", "", false))
        assertEquals("The two passphrases are different.", passProblem("abcdefgh", "abcdefgx", false))
        assertEquals("With keys and passwords included, choose a passphrase that is harder to guess.", passProblem("abcdefgh", "abcdefgh", true))
        // Fair is not enough for keys.
        val fair = "kittenrain42"
        assertEquals(1, Backup.strength(fair).first)
        assertFalse(forKeys(fair))
        assertTrue(forKeys("correct horse battery staple"))
    }

    @Test fun thePassphraseJudge() {
        val g = Random(0x9A55)
        fun <T> pick(l: List<T>) = l[g.nextInt(l.size)]
        val wrong = mutableListOf<String>()
        val stems = listOf("horse", "pass", "word", "test", "summer", "london", "Pass", "ab12")
        val seps = listOf(" ", "-", "_", ".", ",", "/", "+", "|", ":", ";", "*", "~", "", "  ", "\t", "!", "#", "@", "=", "\\")
        repeat(4000) {
            val stem = pick(stems); val sep = pick(seps); val n = 4 + g.nextInt(5); val counter = g.nextBoolean()
            val p = (1..n).joinToString(sep) { stem + if (counter) "$it" else "" }
            if (forKeys(p)) wrong += "repeat: $p"
        }
        for (w in listOf("qwertyuiop", "asdfghjkl;", "zxcvbnm,./", "1qaz2wsx3edc4rfv", "!QAZ2wsx#EDC4rfv", "zaq1xsw2cde3vfr4", "qazwsxedcrfvtgbyhn",
            "1q2w3e4r5t6y7u8i", "qwertzuiopasdfgh", "azertyuiopqsdfgh", "poiuytrewqlkjhgf", "mnbvcxzlkjhgfdsa", "1234qwerasdfzxcv", "q1w2e3r4t5y6u7i8o9p0",
            "pass1 pass2 pass3 pass4", "word1 word2 word3 word4", "abd1 abd2 abd3 abd4", "test1 test2 test3 test4", "aaaaaaaaaaaaaaaaaaaaaaaa", "abcabcabcabcabcabc",
            "Password1234!", "qwertyqwertyqwerty", "unbraid unbraid unbraid unbraid", "1234567890123456", "abcdefghijklmnopqrstuvwxyz", "zyxwvutsrqponmlkjihgfedcba",
            "Aa1!Aa1!Aa1!Aa1!", "horse horse horse horse", "letmein letmein letmein", "password1 password2 password3 password0", "aaaa aaaa aaaa aaaa",
            (1..10).joinToString(" ") { "password${it % 10}" }, (1..10).joinToString(" ") { "${it}password" }, (1..8).joinToString(" ") { "word$it" },
            (1..6).joinToString("+") { "horse$it" }, (1..7).joinToString("") { "pass$it" })) if (forKeys(w)) wrong += "walk: $w ${Backup.strength(w)}"
        // Digits of any script make runs by their value; a short run of them is easy.
        assertEquals(0, Backup.strength("١٢٣٤٥٦٧٨٩٠").first)
        // Equivalent spellings are judged alike.
        for ((a, b) in listOf("café crème brûlée naïve" to "café crème brûlée naïve",
            "ṩṩṩZebra42!" to "ṩṩṩZebra42!", "ñandú pérez" to "ñandú pérez"))
            if (Backup.strength(a) != Backup.strength(b)) wrong += "apart: $a"
        repeat(500) {
            val base = String(CharArray(14) { 'a' + g.nextInt(26) })
            if (Backup.strength(base.replace("e", "é")) != Backup.strength(base.replace("e", "é"))) wrong += "NFC/NFD: $base"
        }
        // Diceware-style: five to seven words from a list of 300, one repeated: hard.
        val syll = listOf("ba", "ko", "ri", "tem", "lu", "mor", "vash", "pel", "dri", "qua", "zon", "fi", "gut", "hes", "jor", "nix", "sal", "wey")
        val list = HashSet<String>().apply { while (size < 300) add(pick(syll) + pick(syll) + if (g.nextBoolean()) pick(syll) else "") }.sorted()
        var weak = 0
        repeat(1000) {
            val phrase = list.shuffled(g).take(5 + g.nextInt(3)).toMutableList()
            phrase.add(g.nextInt(phrase.size + 1), phrase[g.nextInt(phrase.size)])
            val p = phrase.joinToString(pick(listOf(" ", "-", " ")))
            if (!forKeys(p)) { weak++; if (weak <= 5) wrong += "diceware refused: $p" }
        }
        for (stem in listOf("pass", "horse", "Summer", "word")) for (sep in listOf("", " ", "-", "+", "/"))
            (1..20).joinToString(sep) { "$stem$it" }.let { if (forKeys(it)) wrong += "counter series: $it" }
        // Random text in scripts without letter case and emoji, 14 to 20 characters (letters with a case count as a-z does): good enough for keys.
        val scripts = listOf(0x4E00..0x9FFF, 0x627..0x64A, 0xAC00..0xD7A3, 0x5D0..0x5EA, 0x905..0x939, 0x1F300..0x1F5FF, 0x1F600..0x1F64F)
        var refused = 0
        repeat(1000) {
            val r = pick(scripts)
            val p = buildString { repeat(14 + g.nextInt(7)) { val s = if (g.nextInt(100) < 85) r else pick(scripts); appendCodePoint(s.first + g.nextInt(s.last - s.first + 1)) } }
            if (!forKeys(p)) { refused++; if (refused <= 5) wrong += "random refused: $p ${Backup.strength(p)}" }
        }
        // Sixteen random Han characters are hard; random flags too.
        assertEquals(2, Backup.strength("龍一象山水火風雷木金土日月星雲花").first)
        assertEquals(2, Backup.strength("🇳🇱🇯🇵🇧🇷🇨🇦🇮🇹🇰🇪🇳🇴🇦🇺🇲🇽🇮🇳").first)
        // A long repeat at the start doesn't hide what follows.
        val tail = "Xk9#mQ2\$vL7@pR4!"
        assertEquals(Backup.strength("0$tail"), Backup.strength("0".repeat(300) + tail))
        assertEquals(2, Backup.strength("0".repeat(5000) + tail).first)
        val t = System.nanoTime()
        Backup.strength("correct horse ".repeat(20_000))
        assertTrue((System.nanoTime() - t) < 1_000_000_000)
        assertEquals(emptyList<String>(), wrong)
    }
}
