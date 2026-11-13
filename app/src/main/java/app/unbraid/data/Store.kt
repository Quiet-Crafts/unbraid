package app.unbraid.data

import app.unbraid.support.NudgeState
import android.content.Context
import android.util.Log
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A provider account signed out of whose device slot isn't released yet ([Store.signOut]). [blob] holds its keys: sealed.
 * [since] is when it was signed out (0: not known, counted from the first failed try), [tries] the failed tries so far,
 * [next] the earliest time for another. [done]: released, kept only until the account's removal is on disk.
 */
@kotlinx.serialization.Serializable
data class PendingRelease(val provider: String, val blob: String, val since: Long = 0, val tries: Int = 0, val next: Long = 0, val done: Boolean = false) {
    override fun toString() = "PendingRelease($provider)" // never the account itself
    /** The same sign-out, whatever its tries. */
    fun same(o: PendingRelease) = provider == o.provider && blob == o.blob

    /**
     * After a failed try at [now]: each next one waits longer ([RELEASE_WAITS]). Null = give up, the account is erased:
     * [RELEASE_TRIES] tries failed, or the first failed [RELEASE_FOR] ago.
     */
    fun failedAt(now: Long): PendingRelease? {
        val from = since.takeIf { it > 0 } ?: now
        val n = tries + 1
        return if (n >= RELEASE_TRIES || now - from >= RELEASE_FOR) null else copy(since = from, tries = n, next = now + RELEASE_WAITS[n - 1])
    }
}

/** Waits after each failed release (5 minutes up to 3 days), at most this many tries, over at most two weeks. */
val RELEASE_WAITS = listOf(5L, 60, 360, 1_440, 4_320).map { it * 60_000 }
const val RELEASE_TRIES = 6
const val RELEASE_FOR = 14 * 86_400_000L

/** A sign-out ([Store.signOut]): the account to release, and whether its release is kept for retries ([kept]); if not, release it now. */
class SignedOut(val blob: String, val kept: Boolean) {
    override fun toString() = "SignedOut($kept)"
}

/**
 * Everything Unbraid persists. Plain JSON files in private storage; secrets AES-GCM sealed by the Keystore. [dir] and
 * [keyOf] can be swapped for tests.
 */
class Store(private val dir: File, private val keyOf: (create: Boolean) -> SecretKey = ::keystoreKey) {
    constructor(ctx: Context) : this(ctx.filesDir)
    private val json = NordApi.json
    /**
     * For what the user chose (settings, update and support-card state): every value is written, defaults and nulls too,
     * so a default changed in a later version never changes a saved choice. Reads the same as [json].
     */
    private val explicit = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Declared before anything that loads sealed files at construction. A new key only while no sealed file exists: a
    // key missing beside them throws, and a failed lazy runs again on the next use, so those files are never orphaned.
    private val key: SecretKey by lazy { keyOf(listOf(SETUP, ACCOUNTS, TS_STATE, RELEASES, RELEASES_BAD).none { File(dir, it).exists() }) }

    /**
     * True while the saved settings or sign-ins exist but won't open. Nothing changes them then, in memory or on disk,
     * until [reopen] reads them or the user chooses [startFresh]: running on defaults would be another routing policy.
     */
    private val _unreadable = MutableStateFlow(false)
    val unreadable: StateFlow<Boolean> = _unreadable.asStateFlow()
    private val bad = mutableSetOf<String>()
    @Volatile private var tsBad = false
    /** A list of pending releases kept aside was tried this start ([journal]). Declared before the reads at construction. */
    @Volatile private var asideTried = false

    /** Set when a change applied but couldn't be written: it would be gone after a restart, so the UI says so. */
    val unsaved = MutableStateFlow(false)
    private var setupFailed = false
    private var accountsFailed = false
    private var tsFailed = false
    private fun failed(setup: Boolean = setupFailed, accounts: Boolean = accountsFailed, ts: Boolean = tsFailed) {
        setupFailed = setup; accountsFailed = accounts; tsFailed = ts; unsaved.value = setup || accounts || ts
    }

    // Sealed: it can hold imported WireGuard private keys and a Tailscale auth key.
    private val _setup = MutableStateFlow(readSetup() ?: newSetup())
    val setup: StateFlow<Setup> = _setup.asStateFlow()
    private fun readSetup() = marked(SETUP) { explicit.decodeFromString(Setup.serializer(), it).migrated() }

    /** Applies [f]; false when it couldn't be written (see [unsaved]), so callers keep a pasted secret on the clipboard. */
    @Synchronized
    fun update(f: (Setup) -> Setup): Boolean = change(f) && saveSetup()

    /**
     * Applies [f] at once and writes it on [writer], so a tap never waits for the Keystore and the disk. Each write
     * takes the newest setup, so the last one on disk is always the latest; a failed one shows [unsaved], as [update].
     */
    fun edit(f: (Setup) -> Setup) {
        if (!change(f)) return
        if (queued.compareAndSet(false, true)) writer.execute { queued.set(false); synchronized(this) { saveSetup() } }
    }

    /** Waits for the writes [edit] queued so far. */
    fun flush() = writer.submit {}.get()

    // Its own lock, not the one saves hold: a tap's edit never waits for a write under way. Changes still apply one at a time.
    private val changing = Any()
    private val queued = java.util.concurrent.atomic.AtomicBoolean()
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "store").apply { isDaemon = true } }

    // Wherever a route is removed, what used it is mended here: only the routes this change removed, so a setup read with a
    // missing route keeps naming it (the screens say so; the engine refuses or blocks it).
    // False while [unreadable]: nothing changes then. A change that makes a [reserve]d route a default is dropped.
    private fun change(f: (Setup) -> Setup) = synchronized(changing) {
        if (_unreadable.value) false else {
            _setup.update { old ->
                f(old).let { new -> new.tidy(old.routes.map { it.id }.toSet() - new.routes.map { it.id }.toSet()).after(old) }
                    .takeIf { new -> reserved.none { g -> new.refusesRemoving(g(new), "") != null } } ?: old
            }
            true
        }
    }

    /** Routes about to be removed, each set as [reserve] named it: no change makes one of them a default meanwhile. */
    private val reserved = java.util.concurrent.CopyOnWriteArrayList<(Setup) -> Set<String>>()

    /**
     * Holds the routes [gone] picks (named [name]) for a removal with steps that can't be undone (a log-out): refused, and
     * nothing held, while one is a default ([Setup.refusesRemoving]); else no change can make one a default until [unreserve].
     */
    fun reserve(gone: (Setup) -> Set<String>, name: String): CarriesDefault? = synchronized(changing) {
        _setup.value.let { it.refusesRemoving(gone(it), name) } ?: run { reserved += gone; null }
    }

    fun unreserve(gone: (Setup) -> Set<String>) { synchronized(changing) { reserved -= gone } }

    // A Keystore hiccup must not crash a tap: the change stays in memory, the banner says so, and a retry writes it.
    private fun saveSetup() = safely { seal("setup.bin", explicit.encodeToString(Setup.serializer(), _setup.value)) }
        .also { if (!it) Log.e("Unbraid", "store: setup not saved"); failed(setup = !it) }
    private fun saveAccounts() = safely { seal("accounts.bin", json.encodeToString(accountsSer, _accounts.value)) }
        .also { if (!it) Log.e("Unbraid", "store: accounts not saved"); failed(accounts = !it); if (it) settled() }
    private inline fun safely(f: () -> Boolean) = runCatching(f).getOrDefault(false)

    /** Writes what is in memory again, after a failed save (the user's retry). True if all of it is on disk now. */
    @Synchronized
    fun saveAgain(): Boolean {
        if (tsFailed) runCatching { tsState } // reading it seals the old Tailscale state again
        return saveSetup() and saveAccounts() and !tsFailed
    }

    /** Cached server catalog per provider. */
    fun countries(provider: String): List<Country> =
        read("countries-$provider.json")?.let { runCatching { json.decodeFromString(countriesSer, it) }.getOrNull() }.orEmpty()

    /** True when [provider]'s cached catalog is under a day old. One written while the clock was ahead (a time to come) is not. */
    fun countriesFresh(provider: String, now: Long = System.currentTimeMillis()) =
        File(dir, "countries-$provider.json").let { it.exists() && now - it.lastModified() in 0 until 86_400_000 }

    @Synchronized
    fun countries(provider: String, v: List<Country>) = write("countries-$provider.json", json.encodeToString(countriesSer, v))

    /** When each gluetun catalogue last read was published (epoch seconds, [Catalog.published]); kept across restarts. */
    val catalogDates = MutableStateFlow(runCatching { read("catalog-dates.json")?.let { json.decodeFromString(datesSer, it) } }.getOrNull().orEmpty())

    @Synchronized
    fun catalogDate(name: String, epochSeconds: Long) {
        val d = catalogDates.value
        if (d[name] != epochSeconds) (d + (name to epochSeconds)).let { if (write("catalog-dates.json", json.encodeToString(datesSer, it))) catalogDates.value = it }
    }

    /** Last tunnel that worked per "provider|location", used when a provider's API is unreachable. Holds keys, so sealed. */
    val lastLinks: Map<String, Link>
        get() = runCatching { open("links.bin")?.let { json.decodeFromString(linksSer, it) } }.getOrNull().orEmpty()

    /** Merges [add], keeping only providers still signed in (a sign-out may have raced a connect). Only a cache: never throws. */
    @Synchronized
    fun rememberLinks(add: Map<String, Link>) {
        val live = _accounts.value.keys
        if (!safely { seal("links.bin", json.encodeToString(linksSer, (lastLinks + add).filterKeys { it.substringBefore('|') in live })) }) Log.w("Unbraid", "store: last servers not saved")
    }

    /** Signed-in providers: provider id → the sealed account blob its [Provider.signIn] returned. */
    private val _accounts = MutableStateFlow(readAccounts() ?: if (ACCOUNTS in bad) emptyMap() else migrateNord())
    val accounts: StateFlow<Map<String, String>> = _accounts.asStateFlow()
    private fun readAccounts() = marked(ACCOUNTS) { json.decodeFromString(accountsSer, it) }
    init { _unreadable.value = bad.isNotEmpty(); finishSignOuts() }

    /**
     * Sign-outs whose device slot the provider hasn't released yet ([signOut]): kept sealed, retried until [released] or
     * given up ([failedRelease]). Empty when there are none or they won't open ([releasesUnreadable]).
     */
    val pendingReleases: List<PendingRelease>
        @Synchronized get() = journal().orEmpty().filter { !it.done }

    /** The list of pending releases, or one kept aside, won't open: the user is told to check their accounts' devices. */
    val releasesUnreadable: Boolean
        @Synchronized get() = journal() == null || File(dir, RELEASES_BAD).exists()

    private fun signIns() = runCatching { read(SIGN_INS)?.let { json.decodeFromString(datesSer, it) } }.getOrNull().orEmpty()

    private fun decodeJournal(name: String) = runCatching { load(name) { explicit.decodeFromString(releasesSer, it) }.orEmpty() }.getOrNull()

    /**
     * Every pending release, done ones too; null when the list won't open and can't be kept aside. One that won't open is
     * moved to [RELEASES_BAD] (never written over) and a new list starts; once that one opens again, it joins the list.
     */
    private fun journal(): List<PendingRelease>? {
        val bad = File(dir, RELEASES_BAD)
        val now = decodeJournal(RELEASES) ?: run {
            if (bad.exists() || !File(dir, RELEASES).renameTo(bad)) return null
            Log.e("Unbraid", "store: $RELEASES won't open, kept aside")
            emptyList()
        }
        if (!bad.exists() || asideTried) return now
        asideTried = true // once per start: it waits on every try
        val back = decodeJournal(RELEASES_BAD) ?: return now
        // Not one its provider was signed in to again since: its release could remove the device that sign-in uses.
        val signIns = signIns()
        val all = now + back.filter { b ->
            now.none { it.same(b) } && (signIns[b.provider]?.let { b.since >= it } ?: true) && _accounts.value[b.provider].let { it == null || it == b.blob }
        }
        return if (pending(all)) all.also { bad.delete() } else now
    }

    private fun pending(list: List<PendingRelease>) = safely { seal(RELEASES, list.takeIf { it.isNotEmpty() }?.let { explicit.encodeToString(releasesSer, it) }) }

    /** [f] on the list of pending releases, written back; false when it won't open or wasn't written. */
    private fun journal(f: (List<PendingRelease>) -> List<PendingRelease>) = journal()?.let { old -> f(old).let { it == old || pending(it) } } ?: false

    /**
     * Signs out of [provider]. Its account is first kept as a pending release (on disk), then forgotten: a sign-out cut
     * short still releases the slot later, and never leaves the account signed in. When the release can't be kept, the
     * account goes all the same and [SignedOut.kept] is false: release it at once. Null when not signed in (or the settings
     * won't open).
     */
    @Synchronized
    fun signOut(provider: String, now: Long = System.currentTimeMillis()): SignedOut? {
        if (_unreadable.value) return null
        val blob = _accounts.value[provider] ?: return null
        val kept = journal { it + PendingRelease(provider, blob, since = now) }
        if (!kept) Log.w("Unbraid", "store: pending release not saved")
        account(provider, null)
        return SignedOut(blob, kept)
    }

    /** [r] is still waiting and due at [now]: not released, given up or dropped meanwhile (by another screen, or a sign-in). */
    @Synchronized
    fun due(r: PendingRelease, now: Long = System.currentTimeMillis()) = pendingReleases.any { it.same(r) && it.next <= now }

    /**
     * The provider let go of [r]'s slot, or the user was told to remove it by hand: no longer retried. While the account's
     * removal isn't on disk yet, a record stays (done) so a restart still finishes that sign-out.
     */
    @Synchronized
    fun released(r: PendingRelease) {
        journal { l -> if (accountsFailed) l.map { if (it.same(r)) it.copy(done = true) else it } else l.filterNot { it.same(r) } }
    }

    /** [r]'s release failed at [now]: tried later ([PendingRelease.failedAt]). True when given up: its account is erased. */
    @Synchronized
    fun failedRelease(r: PendingRelease, now: Long = System.currentTimeMillis()): Boolean {
        var gaveUp = false
        // Given up: erased, or kept as done (no more tries) while the account's removal isn't on disk yet.
        journal { l -> l.mapNotNull { if (!it.same(r) || it.done) it else it.failedAt(now) ?: it.copy(done = true).takeIf { accountsFailed }.also { gaveUp = true } } }
        return gaveUp
    }

    /** The user was told about a list of releases that won't open: it is deleted, its accounts with it. */
    @Synchronized
    fun forgetUnreadableReleases() {
        if (journal() == null) File(dir, RELEASES).delete()
        File(dir, RELEASES_BAD).delete()
    }

    /** Releases done whose sign-out is on disk now need no record. */
    private fun settled() { journal()?.takeIf { l -> l.any { it.done } }?.let { l -> pending(l.filter { !it.done }) } }

    /** A sign-out cut short between keeping its release and forgetting the account: the user asked, so it is finished now. */
    private fun finishSignOuts() {
        if (!_unreadable.value) journal().orEmpty().filter { _accounts.value[it.provider] == it.blob }.forEach { account(it.provider, null) }
    }

    /** Reads the files that wouldn't open again; what opens goes into memory before the mark clears. True when all did. */
    @Synchronized
    fun reopen(): Boolean = synchronized(changing) {
        if (SETUP in bad) _setup.value = readSetup() ?: _setup.value
        if (ACCOUNTS in bad) _accounts.value = readAccounts() ?: _accounts.value
        _unreadable.value = bad.isNotEmpty()
        finishSignOuts()
        !_unreadable.value
    }

    /**
     * The reset the user confirmed over files that won't open: routes, sign-ins and settings on this phone go, old
     * versions' copies too. Statistics and the onboarding choices stay. True when every file is gone and saving works again.
     */
    @Synchronized
    fun startFresh(): Boolean = synchronized(changing) {
        if (!_unreadable.value) return true // they opened meanwhile (a VPN start reopens them): nothing to reset
        // The old Tailscale sign-in first: if it stays, nothing else goes and the mark stays.
        if (!File(dir, "tailscale").deleteRecursively()) return false
        listOf(SETUP, ACCOUNTS, RELEASES, "links.bin", "token.bin", "nlk.bin", "servers.json", "tssuffix", "expiry",
            "setup.bin.bad", "accounts.bin.bad", "links.bin.bad", RELEASES_BAD, SIGN_INS).forEach { File(dir, it).delete() }
        // The domain table only when no engine has it open (one keeping apps offline meanwhile): that engine deletes it
        // once it has stopped, so a new table never starts in the same block.
        app.unbraid.vpn.LookupTable.forget(dir)
        _setup.value = newSetup(); _accounts.value = emptyMap(); failed(false, false, false); asideTried = false
        bad.removeAll { !File(dir, it).exists() }
        _unreadable.value = bad.isNotEmpty()
        !_unreadable.value
    }

    /**
     * False when the change couldn't be written. It applies in memory anyway: a new sign-in holds a device slot the
     * provider just gave, which only this copy knows; [unsaved] tells the user, and [saveAgain] retries.
     */
    @Synchronized
    fun account(provider: String, blob: String?): Boolean {
        if (_unreadable.value) return false
        // Signed in again: an earlier sign-out's release could remove the device this one uses (one adopted by name, say),
        // and the provider can't tell them apart, so it is no longer tried.
        // The time is noted for a list kept aside ([journal]), which can't be changed now: its releases from before stay out.
        if (blob != null) {
            journal { l -> l.filterNot { it.provider == provider } }
            if (!safely { write(SIGN_INS, json.encodeToString(datesSer, signIns() + (provider to System.currentTimeMillis()))) }) Log.w("Unbraid", "store: sign-in time not saved")
        }
        _accounts.update { if (blob == null) it - provider else it + (provider to blob) }
        val ok = saveAccounts()
        if (blob == null) rememberLinks(emptyMap())
        return ok
    }

    /** Preview builds kept the Nord token and key in their own files. Only while there is no accounts file at all. */
    private fun migrateNord(): Map<String, String> {
        val t = runCatching { open("token.bin") }.getOrNull() ?: return emptyMap()
        val k = runCatching { open("nlk.bin") }.getOrNull() ?: return emptyMap()
        val m = mapOf("nord" to json.encodeToString(Nord.Account.serializer(), Nord.Account(t, k)))
        if (safely { seal("accounts.bin", json.encodeToString(accountsSer, m)) }) listOf("token.bin", "nlk.bin", "servers.json").forEach { File(dir, it).delete() }
        return m
    }


    var updates: UpdateState
        get() = synchronized(this) { runCatching { read("updates.json")?.let { explicit.decodeFromString(UpdateState.serializer(), it) } }.getOrNull() ?: UpdateState() }
        set(v) { synchronized(this) { safely { write("updates.json", explicit.encodeToString(UpdateState.serializer(), v)) } } }

    var nudges: NudgeState
        get() = runCatching { read("nudges.json")?.let { explicit.decodeFromString(NudgeState.serializer(), it) } }.getOrNull() ?: NudgeState()
        set(v) { safely { write("nudges.json", explicit.encodeToString(NudgeState.serializer(), v)) } }

    /** The tailnet's MagicDNS domain, learned once Tailscale is signed in. */
    var tsSuffix: String?
        get() = read("tssuffix")
        set(v) { if (v == null) File(dir, "tssuffix").delete() else write("tssuffix", v) }

    /**
     * Tailscale's node state (node and machine keys, tailnet profile), which tsnet would keep in plaintext. Sealed
     * inside its directory, so every reset that deletes that directory drops it too.
     */
    var tsState: String
        // What older versions left in plaintext goes once the sealed copy reads back. A sealed copy that won't open throws
        // and isn't written over, so the engine can't replace the node; signing out of Tailscale removes it.
        @Synchronized get() {
            val v = runCatching { load(TS_STATE) { it } }.getOrElse { tsBad = true; error(TS_UNREADABLE) }
            tsBad = false
            return v?.also { dropPlainTs() } ?: migrateTs()
        }
        @Synchronized set(v) {
            check(!tsBad || !File(dir, TS_STATE).exists()) { TS_UNREADABLE }
            check(seal(TS_STATE, v)) { "Tailscale state not saved" }; File(dir, TS_PLAIN).delete()
        }

    /** A Tailscale node is kept on this phone (sealed or from an older version), signed in or not. No decryption. */
    val tsStored get() = File(dir, TS_STATE).exists() || File(dir, TS_PLAIN).exists()

    /** Older versions left the state, and a cache of the tailnet map, in plaintext: sealed, and deleted only once sealed. */
    private fun migrateTs(): String {
        val plain = File(dir, TS_PLAIN).takeIf { it.exists() }?.readText() ?: return ""
        if (safely { seal(TS_STATE, plain) }) dropPlainTs() else { Log.w("Unbraid", "store: Tailscale state not sealed yet"); failed(ts = true) }
        return plain
    }

    /** The banner stays until the plaintext is gone too; every sealed read tries again. */
    private fun dropPlainTs() {
        File(dir, "tailscale/profile-data").deleteRecursively()
        failed(ts = File(dir, TS_PLAIN).let { it.exists() && !it.delete() })
    }

    /** Which block of the fake-IP pool the engine's last domain table used ([app.unbraid.vpn.Compiler.FAKE_RANGES]). */
    var fakeBlock: Int
        get() = read("fakeblock")?.toIntOrNull() ?: app.unbraid.vpn.Compiler.blockAfterHalf(read("fakehalf")?.toIntOrNull())
        set(v) = check(write("fakeblock", "$v"))

    /** The user agreed to M-Lab's terms for speed tests (results, with the exit's IP address, are published). */
    var mlabConsent: Boolean
        get() = File(dir, "mlab").exists()
        set(v) { if (v) write("mlab", "1") else File(dir, "mlab").delete() }

    /** Speed tests run today, for M-Lab's limit of 40 a day per user: "epochDay count". */
    var testsToday: Int
        get() = read("mlabday")?.split(' ')?.takeIf { it.getOrNull(0) == "${java.time.LocalDate.now().toEpochDay()}" }?.getOrNull(1)?.toIntOrNull() ?: 0
        set(v) { write("mlabday", "${java.time.LocalDate.now().toEpochDay()} $v") }

    /**
     * A speed test switched Tailscale to another exit node and hasn't switched it back yet: the stable ID of the node in
     * use before ("" for none); null when nothing is to be undone. Older versions wrote "1", which is no node ID.
     */
    var tsTestFrom: String?
        get() = read("tstest")?.takeIf { it != "1" } ?: if (File(dir, "tstest").exists()) "" else null
        set(v) { if (v != null) write("tstest", v) else File(dir, "tstest").delete() }

    fun signOutAll() {
        if (_unreadable.value) return
        _accounts.value.keys.toList().forEach { account(it, null) }
        // Written once more: a removal above whose save failed must not bring that account back after a restart.
        _accounts.value = emptyMap(); saveAccounts()
        update { it.copy(home = it.home.copy(tsAuthKey = null)) }
        File(dir, "expiry").delete() // left by older versions
        tsSuffix = null
        // Copies older versions kept of files that failed to open hold accounts and keys too.
        listOf("links.bin", "cache.db", "setup.bin.bad", "accounts.bin.bad", "links.bin.bad").forEach { File(dir, it).delete() }
        File(dir, "tailscale").deleteRecursively()
    }

    private fun read(name: String) = File(dir, name).takeIf { it.exists() }?.readText()

    private fun write(name: String, text: String) = durable(name, text.toByteArray())

    /** Replaces [name] with [bytes] in one rename, the data synced to disk first so a crash can't leave it half written. */
    private fun durable(name: String, bytes: ByteArray): Boolean {
        val tmp = File(dir, "$name.tmp")
        return try {
            FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
            tmp.renameTo(File(dir, name))
        } finally { tmp.delete() }
    }

    /**
     * Reads a sealed file: null when there is none. One that exists but won't open (a Keystore hiccup, corruption) is
     * tried once more after a short pause, then throws; the file stays as it is.
     */
    private fun <T> load(name: String, decode: (String) -> T): T? {
        var last: Throwable? = null
        repeat(2) { n ->
            if (n > 0) Thread.sleep(200)
            runCatching { return open(name)?.let(decode) }.onFailure { last = it }
        }
        throw IllegalStateException("$name won't open", last)
    }

    /** [load] for the files [unreadable] guards: one that won't open is noted in [bad], and null is returned. */
    private fun <T> marked(name: String, decode: (String) -> T): T? =
        runCatching { load(name, decode) }.onSuccess { bad -= name }.getOrElse { Log.e("Unbraid", "store: $name won't open"); bad += name; null }

    private fun seal(name: String, value: String?): Boolean {
        if (_unreadable.value && (name == SETUP || name == ACCOUNTS)) return false
        if (value == null) return File(dir, name).delete()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return durable(name, c.iv + c.doFinal(value.toByteArray()))
    }

    /** Null when [name] doesn't exist; throws when it won't read or open. */
    private fun open(name: String): String? {
        val b = File(dir, name).takeIf { it.exists() }?.readBytes() ?: return null
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b, 0, 12)) }
        return String(c.doFinal(b, 12, b.size - 12))
    }

    private companion object {
        const val ALIAS = "unbraid"
        const val SETUP = "setup.bin"
        const val ACCOUNTS = "accounts.bin"
        const val RELEASES = "releases.bin"
        const val RELEASES_BAD = "releases.bin.bad"
        /** When each provider was last signed in to (epoch ms): no provider data, only for [journal]. */
        const val SIGN_INS = "signins.json"
        const val TS_UNREADABLE = "Couldn't open the saved Tailscale sign-in. Try again. If it keeps failing, sign out of Tailscale in Settings and sign in again."
        const val TS_STATE = "tailscale/state.bin"
        const val TS_PLAIN = "tailscale/tailscaled.state"
        val countriesSer = ListSerializer(Country.serializer())
        val linksSer = MapSerializer(String.serializer(), Link.serializer())
        val accountsSer = MapSerializer(String.serializer(), String.serializer())
        val releasesSer = ListSerializer(PendingRelease.serializer())
        val datesSer = MapSerializer(String.serializer(), Long.serializer())

        /** The app's key; a new one only when [create] (no sealed file it would orphan), else a missing one throws. */
        fun keystoreKey(create: Boolean): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            check(create) { "Keystore key missing" }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
            }.generateKey()
        }
    }
}
