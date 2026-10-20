package app.unbraid.data

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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Everything Unbraid persists. Plain JSON files in private storage; secrets AES-GCM sealed by the Keystore. */
class Store(ctx: Context) {
    private val dir = ctx.filesDir
    private val json = NordApi.json

    // Declared before anything that loads sealed files at construction.
    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    // Sealed: it can hold imported WireGuard private keys and a Tailscale auth key.
    private val _setup = MutableStateFlow(load("setup.bin") { json.decodeFromString(Setup.serializer(), it) } ?: Setup())
    val setup: StateFlow<Setup> = _setup.asStateFlow()

    /** Set when a change applied but couldn't be written: it would be gone after a restart, so the UI says so. */
    val unsaved = MutableStateFlow(false)

    /** Applies [f]; false when it couldn't be written (see [unsaved]), so callers keep a pasted secret on the clipboard. */
    @Synchronized
    fun update(f: (Setup) -> Setup): Boolean {
        _setup.update(f)
        // A Keystore hiccup must not crash a tap; the next change writes again.
        val ok = runCatching { seal("setup.bin", json.encodeToString(Setup.serializer(), _setup.value)) }.getOrDefault(false)
        if (!ok) Log.e("Unbraid", "store: setup not saved")
        unsaved.value = !ok
        return ok
    }

    /** Cached server catalog per provider. */
    fun countries(provider: String): List<Country> =
        read("countries-$provider.json")?.let { runCatching { json.decodeFromString(countriesSer, it) }.getOrNull() }.orEmpty()

    /** True when [provider]'s cached catalog is under a day old. */
    fun countriesFresh(provider: String) = File(dir, "countries-$provider.json").let { it.exists() && System.currentTimeMillis() - it.lastModified() < 86_400_000 }

    @Synchronized
    fun countries(provider: String, v: List<Country>) = write("countries-$provider.json", json.encodeToString(countriesSer, v))

    /** Last tunnel that worked per "provider|location", used when a provider's API is unreachable. Holds keys, so sealed. */
    val lastLinks: Map<String, Link>
        get() = open("links.bin")?.let { runCatching { json.decodeFromString(linksSer, it) }.getOrNull() }.orEmpty()

    /** Merges [add], keeping only providers still signed in (a sign-out may have raced a connect). */
    @Synchronized
    fun rememberLinks(add: Map<String, Link>) {
        val live = _accounts.value.keys
        seal("links.bin", json.encodeToString(linksSer, (lastLinks + add).filterKeys { it.substringBefore('|') in live }))
    }

    /** Signed-in providers: provider id → the sealed account blob its [Provider.signIn] returned. */
    private val _accounts = MutableStateFlow(
        load("accounts.bin") { json.decodeFromString(accountsSer, it) } ?: migrateNord(),
    )
    val accounts: StateFlow<Map<String, String>> = _accounts.asStateFlow()

    /** False when the change couldn't be written. */
    @Synchronized
    fun account(provider: String, blob: String?): Boolean {
        _accounts.update { if (blob == null) it - provider else it + (provider to blob) }
        val ok = seal("accounts.bin", json.encodeToString(accountsSer, _accounts.value))
        if (blob == null) rememberLinks(emptyMap())
        return ok
    }

    /** Preview builds kept the Nord token and key in their own files. */
    private fun migrateNord(): Map<String, String> {
        val t = open("token.bin") ?: return emptyMap()
        val k = open("nlk.bin") ?: return emptyMap()
        val m = mapOf("nord" to json.encodeToString(Nord.Account.serializer(), Nord.Account(t, k)))
        if (seal("accounts.bin", json.encodeToString(accountsSer, m))) listOf("token.bin", "nlk.bin", "servers.json").forEach { File(dir, it).delete() }
        return m
    }


    /** The tailnet's MagicDNS domain, learned once Tailscale is signed in. */
    var tsSuffix: String?
        get() = read("tssuffix")
        set(v) { if (v == null) File(dir, "tssuffix").delete() else write("tssuffix", v) }

    /**
     * Tailscale's node state (node and machine keys, tailnet profile), which tsnet would keep in plaintext. Sealed
     * inside its directory, so every reset that deletes that directory drops it too.
     */
    var tsState: String
        @Synchronized get() = load(TS_STATE) { it } ?: migrateTs()
        @Synchronized set(v) { check(seal(TS_STATE, v)) { "Tailscale state not saved" }; File(dir, TS_PLAIN).delete() }

    /** Older versions left the state, and a cache of the tailnet map, in plaintext. */
    private fun migrateTs(): String {
        val plain = File(dir, TS_PLAIN).takeIf { it.exists() }?.readText() ?: return ""
        runCatching { tsState = plain }
        File(dir, "tailscale/profile-data").deleteRecursively()
        return plain
    }

    /** Which half of the fake-IP pool the engine's last domain table used ([app.unbraid.vpn.Compiler.FAKE_RANGES]). */
    var fakeHalf: Int
        get() = read("fakehalf")?.toIntOrNull() ?: 0
        set(v) = check(write("fakehalf", "$v"))

    /** The user agreed to M-Lab's terms for speed tests (results, with the exit's IP address, are published). */
    var mlabConsent: Boolean
        get() = File(dir, "mlab").exists()
        set(v) { if (v) write("mlab", "1") else File(dir, "mlab").delete() }

    /** Speed tests run today, for M-Lab's limit of 40 a day per user: "epochDay count". */
    var testsToday: Int
        get() = read("mlabday")?.split(' ')?.takeIf { it.getOrNull(0) == "${java.time.LocalDate.now().toEpochDay()}" }?.getOrNull(1)?.toIntOrNull() ?: 0
        set(v) { write("mlabday", "${java.time.LocalDate.now().toEpochDay()} $v") }

    /** A speed test switched Tailscale to another exit node and hasn't switched it back yet. */
    var tsTestNode: Boolean
        get() = File(dir, "tstest").exists()
        set(v) { if (v) write("tstest", "1") else File(dir, "tstest").delete() }

    fun signOutAll() {
        _accounts.value.keys.toList().forEach { account(it, null) }
        update { it.copy(home = it.home.copy(tsAuthKey = null)) }
        File(dir, "expiry").delete() // left by older versions
        tsSuffix = null
        listOf("links.bin", "cache.db").forEach { File(dir, it).delete() }
        File(dir, "tailscale").deleteRecursively()
    }

    private fun read(name: String) = File(dir, name).takeIf { it.exists() }?.readText()

    private fun write(name: String, text: String): Boolean {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        return tmp.renameTo(File(dir, name))
    }

    /**
     * Reads a sealed file. One that exists but won't open (Keystore reset, corruption) is kept as "<name>.bad" rather
     * than silently replaced by defaults on the next write.
     */
    private fun <T> load(name: String, decode: (String) -> T): T? {
        val text = open(name)
        val v = text?.let { runCatching { decode(it) }.getOrNull() }
        if (v == null && File(dir, name).exists()) runCatching { File(dir, name).copyTo(File(dir, "$name.bad"), overwrite = true) }
        return v
    }

    private fun seal(name: String, value: String?): Boolean {
        val f = File(dir, name)
        if (value == null) return f.delete()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(c.iv + c.doFinal(value.toByteArray()))
        return tmp.renameTo(f)
    }

    private fun open(name: String): String? {
        val b = File(dir, name).takeIf { it.exists() }?.readBytes() ?: return null
        return runCatching {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, 0, 12)) }
            String(c.doFinal(b, 12, b.size - 12))
        }.getOrNull()
    }

    private fun key(): SecretKey = key


    private companion object {
        const val ALIAS = "unbraid"
        const val TS_STATE = "tailscale/state.bin"
        const val TS_PLAIN = "tailscale/tailscaled.state"
        val countriesSer = ListSerializer(Country.serializer())
        val linksSer = MapSerializer(String.serializer(), Link.serializer())
        val accountsSer = MapSerializer(String.serializer(), String.serializer())
    }
}
