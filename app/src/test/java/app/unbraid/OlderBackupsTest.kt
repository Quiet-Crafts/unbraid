package app.unbraid

import app.unbraid.data.Backup
import app.unbraid.data.OwnServer
import app.unbraid.data.Setup
import app.unbraid.data.refilled
import app.unbraid.data.restoredFrom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Backups unbraid 3.0.2 wrote, and what 3.0.2 restored from each over a phone's setup, recorded by 3.0.2's own code
 * (`backups-3.0.2.json.gz`: names with invisible and direction characters, Tailscale names with spaces, odd place
 * names, 5,001 app choices, routes with no own server, with keys and without, over a new phone, the phone that made
 * it, another phone and a phone whose route of the same name points elsewhere, keeping the current setup or not).
 * Every one opens here and restores to what 3.0.2 restored, but for the differences meant, listed in [intended].
 */
class OlderBackupsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * What this version restores differently, on purpose:
     * 1. A provider's pinned server keeps only its name and host, and IVPN's block list starts from the standard one
     *    (compared without either, [plain]).
     * 2. A route saved without keys that had no own server (one was made up in the file) gets its config back from this
     *    phone's same route, only when that route's config, stripped of its secrets here, equals the file's entry. An
     *    OpenVPN file carries only a name, so the route waits for its config, as in 3.0.2.
     * 3. Then the kept current setup shares that route ([shared]).
     * 4. Display-only texts are cleaned on read ([Backup.shown]; compared cleaned, [plain]).
     */
    private fun intended(expected: Setup, file: Setup, phone: Setup): Setup = file.servers.filter { k ->
        k.keyless && phone.servers.none { !it.keyless && it.id == k.id && k.sameAs(it) }
    }.mapNotNull { k ->
        file.routes.filter { it.own == k.id }.firstNotNullOfOrNull { x ->
            phone.route(x.id)?.takeIf { it.own == null && it.imported && it.tsExit == null && !it.keyless }?.let { OwnServer(k.id, it.wg, it.ovpn, it.ss) }
                ?.takeIf { k.ovpn == null && stripped(it) == k }
        }?.let { k.id to it }
    }.fold(expected) { s, (id, sv) -> s.refilled(id, sv) ?: s }

    /** [o] with its keys and passwords taken out by hand, as a file saved without keys holds it. */
    private fun stripped(o: OwnServer) = o.copy(keyless = true, wg = o.wg?.copy(privateKey = "", presharedKey = null), ss = o.ss?.copy(password = "", pluginOpts = null))

    /**
     * 3. The current setup kept as a profile shares a route difference 2 filled in (in [s], not in [was]) instead of
     *    getting a copy of it under a made-up id.
     */
    private fun shared(s: Setup, was: Setup, known: Set<String>): Setup {
        val filled = s.routes.filter { r -> !r.keyless && was.route(r.id)?.keyless == true }
        val copies = s.routes.filter { it.id !in known }.mapNotNull { c -> filled.firstOrNull { it.copy(id = c.id, own = null) == c.copy(own = null) }?.let { c.id to it.id } }.toMap()
        if (copies.isEmpty()) return s
        val m = { id: String -> copies[id] ?: id }
        return s.copy(routes = s.routes.filter { it.id !in copies }, profiles = s.profiles.map { p ->
            p.copy(defaultRoute = m(p.defaultRoute), apps = p.apps.mapValues { m(it.value) }, sites = p.sites.mapValues { m(it.value) }, added = p.added.map(m)) })
    }

    /** [s] without what differences 1 and 4 change, and with ids made up by the restore numbered in order. */
    private fun plain(raw: Setup, known: Set<String>): Setup {
        val s = Backup.shown(raw)
        val made = (s.routes.map { it.id } + s.servers.map { it.id }).filter { it !in known }.withIndex().associate { (i, id) -> id to "made$i" }
        val m = { id: String -> made[id] ?: id }
        // The format number moves with every format (4 for Via); what it holds is what is compared.
        return s.copy(version = 0,
            routes = s.routes.map { it.copy(id = m(it.id), own = it.own?.let(m), loc = Backup.bare(it.loc)) }, servers = s.servers.map { it.copy(id = m(it.id)) },
            favorites = s.favorites.map(Backup::bare), opts = s.opts.mapValues { (p, o) -> if (p == "ivpn") o - "list" else o },
            defaultRoute = m(s.defaultRoute), apps = s.apps.mapValues { m(it.value) }, sites = s.sites.mapValues { m(it.value) }, added = s.added.map(m),
            profiles = s.profiles.map { p -> p.copy(defaultRoute = m(p.defaultRoute), apps = p.apps.mapValues { m(it.value) }, sites = p.sites.mapValues { m(it.value) }, added = p.added.map(m)) },
        )
    }

    @Test fun everyBackupThreeZeroTwoWroteRestoresAsItDid() {
        val all = json.parseToJsonElement(GZIPInputStream(javaClass.classLoader!!.getResourceAsStream("backups-3.0.2.json.gz")!!).bufferedReader().readText()).jsonObject
        val now = all.getValue("now").jsonPrimitive.long
        val files = all.getValue("files").jsonArray.map { it.jsonPrimitive.content.toByteArray() }
        val phones = all.getValue("phones").jsonArray.map { json.decodeFromJsonElement(Setup.serializer(), it) }
        val cases = all.getValue("cases").jsonArray.map { it.jsonObject }
        val failed = mutableListOf<String>()
        var refilled = 0
        for (c in cases) {
            val name = c.str("name")!!
            val phone = phones[c.getValue("phone").jsonPrimitive.int]
            val opened = runCatching { Backup.read(files[c.getValue("file").jsonPrimitive.int]) }.getOrElse { failed += "$name: refused (${it.message})"; continue }
            val got = phone.restoredFrom(opened.setup, c.str("keepAs"), now, opened.keys) ?: run { failed += "$name: no room"; continue }
            val was = json.decodeFromJsonElement(Setup.serializer(), c.getValue("expected"))
            val expected = intended(was, opened.setup, phone).also { if (it != was) refilled++ }
            val known = (opened.setup.routes + phone.routes).map { it.id }.toSet() + (opened.setup.servers + phone.servers).map { it.id }
            if (plain(shared(expected, was, known), known) != plain(got, known)) failed += "$name: restores to another setup"
        }
        assertEquals(failed.joinToString("\n"), 0, failed.size)
        // The file holds what it says: every kind of case, and difference 2 is met (WireGuard and Shadowsocks only).
        assertTrue(cases.size >= 250 && refilled > 0)
        assertTrue(listOf("5001 app choices", "tailscale name", "unlinked .21, no keys, unlinked .22 phone", "place", "full name").all { k -> cases.any { k in it.str("name")!! } })
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
}
