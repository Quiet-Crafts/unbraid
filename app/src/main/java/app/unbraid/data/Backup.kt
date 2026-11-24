@file:OptIn(app.unbraid.data.SavedSites::class)
package app.unbraid.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A backup file: the user's setup sealed under a passphrase. Layout (version 1), all integers big-endian:
 *
 *     magic "UNBRAID-BACKUP\n" (15) | format 1 (1) | kdf 1 = scrypt (1) | log2 N, r, p (1 each) | salt (16) |
 *     nonce (12) | check (16) | length of what follows (4) | AES-256-GCM ciphertext + tag | SHA-256 of all before (32)
 *
 * scrypt turns the passphrase (NFC, UTF-8) into 64 bytes: the first 32 are the AES key, the last 32 key an HMAC whose
 * first 16 bytes are the check, so a wrong passphrase is told apart from a damaged file. The header is the cipher's
 * additional data. The plain SHA-256 at the end tells a damaged or cut file apart before any key is derived.
 */
object Backup {
    private val MAGIC = "UNBRAID-BACKUP\n".toByteArray(Charsets.US_ASCII)
    const val FORMAT = 1
    private const val SCRYPT = 1
    /** 64 MiB and two passes: an OWASP-listed scrypt setting, within any phone's memory for one app. */
    const val LOG_N = 16
    const val R = 8
    const val P = 2
    private const val SALT = 16
    private const val NONCE = 12
    private const val CHECK = 16
    private const val SUM = 32
    private const val TAG = 16
    private val HEAD = MAGIC.size + 5 + SALT + NONCE + CHECK + 4
    /** No setup comes near this; a larger file is refused before it is read whole. */
    const val MAX = 4 shl 20
    /** The most app choices a setup may hold for the restore sheet to check it in full; a backup with more still opens. */
    const val MAX_APPS = 5000
    /** The most routes, and the most own servers, a setup may hold ([valid]). */
    const val MAX_EACH = 500

    enum class Reason(val text: String) {
        NotBackup("This file isn't an unbraid backup."),
        TooLarge("This file is too large to be an unbraid backup."),
        Newer("This backup was made by a newer unbraid. Update unbraid, then restore it."),
        Damaged("This backup is damaged or incomplete. Use another copy of it."),
        Passphrase("That passphrase doesn't open this backup. Check it and try again."),
        Memory("There isn't enough free memory to do this now. Close other apps and try again."),
        Invalid("This backup can't be restored."),
        TooBig("Your setup is too large for one backup file. Remove some own servers, then try again."),
        OtherPlatform("This backup was made by unbraid for Mac. Moving a setup between Mac and Android isn't supported yet."),
        Unrestorable("Nothing was saved, because this backup couldn't be restored"),
    }

    class Failed(val reason: Reason, val detail: String? = null) : Exception(detail?.let { "${reason.text.removeSuffix(".")}: $it" } ?: reason.text)

    private fun fail(r: Reason, detail: String? = null): Nothing = throw Failed(r, detail)

    /** Starts like a backup: worth asking for its passphrase. */
    fun looksLike(b: ByteArray) = b.size >= MAGIC.size && MAGIC.indices.all { b[it] == MAGIC[it] }

    /** What can be said of [b] without the passphrase: null when it may open, else why it can't. */
    fun precheck(b: ByteArray): Reason? = when {
        b.size > MAX -> Reason.TooLarge
        !looksLike(b) -> Reason.NotBackup
        b.size <= MAGIC.size -> Reason.Damaged
        (b[MAGIC.size].toInt() and 0xff) > FORMAT -> Reason.Newer
        b[MAGIC.size].toInt() != FORMAT || b.size < HEAD + TAG + SUM -> Reason.Damaged
        !MessageDigest.isEqual(sha256(b, b.size - SUM), b.copyOfRange(b.size - SUM, b.size)) -> Reason.Damaged
        else -> null
    }

    /** Settings a file may ask for: at most 128 MiB and twice the default's work, so a hostile file can't exhaust the phone. */
    internal fun allowed(logN: Int, r: Int, p: Int) = logN in 10..20 && r in 1..16 && p in 1..4 &&
        (r.toLong() shl logN) * 128 <= 128L shl 20 && p.toLong() * (r.toLong() shl logN) <= 1L shl 21

    fun seal(plain: ByteArray, pass: String, logN: Int = LOG_N, r: Int = R, p: Int = P, rnd: SecureRandom = SecureRandom()): ByteArray {
        require(allowed(logN, r, p))
        val salt = ByteArray(SALT).also(rnd::nextBytes)
        val nonce = ByteArray(NONCE).also(rnd::nextBytes)
        val k = derive(pass, salt, logN, r, p)
        try {
            val head = ByteBuffer.allocate(HEAD).put(MAGIC).put(FORMAT.toByte()).put(SCRYPT.toByte())
                .put(logN.toByte()).put(r.toByte()).put(p.toByte()).put(salt).put(nonce).put(check(k)).putInt(plain.size + TAG).array()
            val body = head + gcm(Cipher.ENCRYPT_MODE, k, nonce, head).doFinal(plain)
            // Never a file that restoring would refuse.
            if (body.size + SUM > MAX) fail(Reason.TooBig)
            return body + sha256(body, body.size)
        } finally { k.fill(0) }
    }

    /** The plaintext of backup [b]; throws [Failed] with why it won't open. */
    fun open(b: ByteArray, pass: String): ByteArray {
        precheck(b)?.let { fail(it) }
        val buf = ByteBuffer.wrap(b, MAGIC.size + 1, HEAD - MAGIC.size - 1)
        val kdf = buf.get().toInt()
        val logN = buf.get().toInt() and 0xff
        val r = buf.get().toInt() and 0xff
        val p = buf.get().toInt() and 0xff
        if (kdf != SCRYPT || !allowed(logN, r, p)) fail(Reason.Damaged)
        val salt = ByteArray(SALT).also { buf.get(it) }
        val nonce = ByteArray(NONCE).also { buf.get(it) }
        val chk = ByteArray(CHECK).also { buf.get(it) }
        val len = buf.getInt()
        if (len != b.size - SUM - HEAD) fail(Reason.Damaged)
        val k = derive(pass, salt, logN, r, p)
        try {
            if (!MessageDigest.isEqual(check(k), chk)) fail(Reason.Passphrase)
            return runCatching { gcm(Cipher.DECRYPT_MODE, k, nonce, b.copyOfRange(0, HEAD)).doFinal(b, HEAD, len) }.getOrElse { fail(Reason.Damaged) }
        } finally { k.fill(0) }
    }

    private fun derive(pass: String, salt: ByteArray, logN: Int, r: Int, p: Int): ByteArray {
        val pw = Normalizer.normalize(pass, Normalizer.Form.NFC).toByteArray(Charsets.UTF_8)
        return try { Scrypt.derive(pw, salt, 1 shl logN, r, p, 64) } catch (_: OutOfMemoryError) { fail(Reason.Memory) } finally { pw.fill(0) }
    }

    private fun check(k: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(k, 32, 32, "HmacSHA256")); doFinal("unbraid backup check".toByteArray()).copyOf(CHECK)
    }

    private fun gcm(mode: Int, k: ByteArray, nonce: ByteArray, head: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, SecretKeySpec(k, 0, 32, "AES"), GCMParameterSpec(TAG * 8, nonce)); updateAAD(head)
    }

    private fun sha256(b: ByteArray, n: Int) = MessageDigest.getInstance("SHA-256").apply { update(b, 0, n) }.digest()

    /** Every passphrase has at least this many characters people see. */
    const val MIN_PASS = 8

    /**
     * How hard [pass] is to guess: 0 easy, 1 fair, 2 hard, and a sentence. Server keys go in a file only under a hard one.
     * It is judged with every decorated form folded to its plain one (NFKC: ① is 1, ａ is a; the file's key is still made
     * from the NFC form), in lower case, cut into words at spaces and ASCII punctuation:
     * - each word counts once: a word whose stem (the word without a number at its start or end) came before adds
     *   nothing, nor does the punctuation after it, so "pass1 pass2 pass3" is one word; spaces add nothing;
     * - a common password counts as one character;
     * - in what is left, a character adds nothing when it repeats the one before, starts a stretch of two or more that
     *   already appeared (and the number right after such a stretch of three or more: "pass1pass2"), or continues a
     *   walk: a step to a neighbouring letter, digit or numeral in its counting order (abc, 321, αβγ, 一二三, ➀➁➂, in any script) or key (qaz, asd; a shifted
     *   digit is its key), after such a step, or the same with every other character (q1w2e3).
     * Runs of one character count once before anything else, then up to 4,096 characters are looked at, so a long
     * repeat at the start doesn't hide what follows. Hard: four or more different words of three characters or more
     * that count for 12 characters together, or 72 bits from the counted characters: a plain one (ASCII, a letter with a
     * case or a digit in any script) by the kinds of plain character used, any other (Han, emoji) 7, or 11 when those
     * make up most of it. It catches obvious patterns only, and says so.
     */
    fun strength(pass: String): Pair<Int, String> {
        val p = Normalizer.normalize(pass, Normalizer.Form.NFKC)
        val (words, kept) = distinctWords(p)
        val chars = guessable(kept)
        val plain = clusters(p).filter(::plainChar)
        val pool = listOf(26 to plain.any { it.codePointAt(0).let(Character::isLowerCase) }, 26 to plain.any { it.codePointAt(0).let(Character::isUpperCase) },
            10 to plain.any(::digit), 33 to plain.any { it.codePointAt(0).let { c -> !Character.isLetter(c) && !Character.isDigit(c) && !Character.isWhitespace(c) } })
            .sumOf { if (it.second) it.first else 0 }.coerceAtLeast(2)
        val other = chars.count { !plainChar(it) }
        val bits = (chars.size - other) * Math.log(pool.toDouble()) / Math.log(2.0) + other * (if (2 * other > chars.size) 11 else 7)
        val four = words.count { it.size >= 3 && guessable(it).size >= 3 } >= 4 && chars.size >= 12
        return when {
            four || bits >= 72 -> 2 to "No obvious pattern found: the meter knows common ones only."
            bits >= 50 -> 1 to "Fair. Longer is safer: four or more words work well."
            else -> 0 to "Easy to guess. Four or more different words work well."
        }
    }

    /** Passwords everyone tries first: each counts as one character wherever it appears. */
    private val COMMON = listOf("password", "passw0rd", "qwerty", "azerty", "letmein", "welcome", "iloveyou", "admin", "unbraid", "monkey", "dragon", "123456")

    /** Only format 2 carries compact labels. Earlier readers can deserialize it and ask the user to update. */
    private fun expandWgLabels(c: Contents): Setup {
        val s = c.setup
        need(s.routes.size <= MAX_EACH && s.servers.size <= MAX_EACH, "it holds more than unbraid keeps")
        val labels = s.servers.any { it.wgIdentity != null } || s.routes.any { it.wgIdentity != null }
        need(labels == (c.format == WG_LABELS), "its WireGuard labels don't match the backup format")
        if (!labels) return s
        val waitingWg = s.servers.filter { it.wgIdentity != null }.mapTo(HashSet()) { it.id }
        need(s.routes.all { (it.wgIdentity != null) == (it.own in waitingWg) }, "a WireGuard label has no matching own server")
        if (!c.keys) need(s.home.wg == null && s.servers.all { it.keyless }, "a backup without keys holds a server config")
        fun peer(label: WgIdentity?, wg: WgPeer?, keyless: Boolean, other: Boolean): WgPeer? {
            if (label == null) {
                need(wg == null || (!keyless && c.keys), "a saved WireGuard peer needs a label")
                return wg
            }
            need(keyless && wg == null && !other, "a WireGuard label conflicts with its configuration")
            return label.waiting()
        }
        return s.copy(
            servers = s.servers.map { it.copy(wg = peer(it.wgIdentity, it.wg, it.keyless, it.ovpn != null || it.ss != null || it.socks5 != null), wgIdentity = null) },
            routes = s.routes.map { it.copy(wg = peer(it.wgIdentity, it.wg, it.keyless, it.ovpn != null || it.ss != null || it.socks5 != null || it.tsExit != null), wgIdentity = null) },
        )
    }

    // The first keyless writer emitted all four of these defaults, even empty DNS and null keys.
    // A sparse historical input still restores its routes, but deserializer defaults cannot prove an exact match.
    @Serializable private class OldWgFields(val dns: List<String>? = null, val mtu: Int? = null,
        val keepalive: Int? = -1, val presharedKey: String? = "missing") {
        val complete get() = dns != null && mtu != null && keepalive != -1 && presharedKey == null
    }
    @Serializable private class OldRecord(val id: String, val wg: OldWgFields? = null)
    @Serializable private class OldRecords(val servers: List<OldRecord> = emptyList(), val routes: List<OldRecord> = emptyList())
    @Serializable private class OldContents(val setup: OldRecords)

    private fun checkLegacyWgFields(s: Setup, text: String): Setup {
        if (s.servers.none { it.keyless && it.wg != null } && s.routes.none { it.keyless && it.wg != null }) return s
        // The typed reader skips unrelated configuration, including large app maps. Validation already bounded the records and DNS lists.
        val records = json.decodeFromString(OldContents.serializer(), text).setup
        fun incomplete(items: List<OldRecord>) = items.filter { it.wg?.complete == false }.mapTo(HashSet()) { it.id }
        val servers = incomplete(records.servers)
        val routes = incomplete(records.routes)
        return s.copy(servers = s.servers.map { if (it.keyless && it.id in servers) it.withoutSecrets() else it },
            routes = s.routes.map { if (it.keyless && it.id in routes) it.withoutSecrets() else it })
    }

    /** The characters people see in [s]. */
    private fun clusters(s: String): List<String> = java.text.BreakIterator.getCharacterInstance().run {
        setText(s); var a = first(); generateSequence { next().takeIf { it != java.text.BreakIterator.DONE }?.let { b -> s.substring(a, b).also { a = b } } }.toList()
    }

    private fun digit(c: String) = Character.isDigit(c.codePointAt(0)) && c.codePointCount(0, c.length) == 1
    private fun plainChar(c: String) = (c.length == 1 && c[0].code < 128) || c.codePointAt(0).let { Character.isLowerCase(it) || Character.isUpperCase(it) } || digit(c)

    /** The words of [p] in lower case, each common password one character, each stem once ([strength]), and the characters that count. */
    private fun distinctWords(p: String): Pair<List<List<String>>, List<String>> {
        var t = p.lowercase(java.util.Locale.ROOT)
        for (w in COMMON) t = t.replace(w, "\u0001")
        val sep = { c: String -> c.isBlank() || (c.length == 1 && c[0].code < 128 && !c[0].isLetterOrDigit() && c != "\u0001") }
        // Pieces: a word and the separators after it. Runs of one character first, then the cap.
        val pieces = mutableListOf(mutableListOf<String>() to mutableListOf<String>())
        clusters(t).fold(mutableListOf<String>()) { a, c -> if (a.lastOrNull() != c) a.add(c); a }.take(4096).forEach { c ->
            val last = pieces.last()
            if (sep(c)) last.second.add(c) else if (last.second.isEmpty()) last.first.add(c) else pieces.add(mutableListOf(c) to mutableListOf())
        }
        val stems = HashSet<List<String>>()
        val words = mutableListOf<List<String>>()
        val kept = mutableListOf<String>()
        for ((word, after) in pieces) {
            val stem = word.dropWhile(::digit).dropLastWhile(::digit)
            if (word.isNotEmpty() && !stems.add(stem.ifEmpty { word })) continue
            if (word.isNotEmpty()) words.add(word)
            kept += word + after.filterNot { it.isBlank() }
        }
        return words to kept
    }

    /** Neighbouring letters and digits on a QWERTY, QWERTZ or AZERTY keyboard, both ways, as two-character strings. */
    private val KEYBOARD: Set<String> = buildSet {
        for (rows in listOf(listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm"), listOf("1234567890", "qwertzuiop", "asdfghjkl", "yxcvbnm"),
            listOf("1234567890", "azertyuiop", "qsdfghjklm", "wxcvbn"))) {
            val keys = rows.flatMapIndexed { y, row -> row.mapIndexed { x, ch -> Triple(ch, x + listOf(0.0, 0.5, 0.75, 1.25)[y], y) } }
            for (a in keys) for (b in keys) if (a.first != b.first && Math.abs(a.third - b.third) <= 1 && Math.abs(a.second - b.second) <= 1) add("${a.first}${b.first}")
        }
    }

    private fun letter(c: Int) = Character.isLetter(c) || Character.getType(c) == Character.LETTER_NUMBER.toInt()
    private fun number(c: String) = c.codePointCount(0, c.length) == 1 && Character.getType(c.codePointAt(0)).let {
        it == Character.DECIMAL_DIGIT_NUMBER.toInt() || it == Character.LETTER_NUMBER.toInt() || it == Character.OTHER_NUMBER.toInt() }

    /**
     * Counting sequences whose neighbours aren't neighbours in Unicode: Chinese and Japanese numerals (一二三, 壹贰叁),
     * the stems and branches, the kana and Hangul orders, the Hebrew and Arabic alphabets. A step between neighbours is a
     * walk, as 123 or abc is.
     */
    private val COUNTED: Set<String> = buildSet {
        for (s in listOf("〇一二三四五六七八九十", "零一二三四五六七八九十百千万", "壹贰叁肆伍陆柒捌玖拾", "壹貳參肆伍陸柒捌玖拾", "甲乙丙丁戊己庚辛壬癸", "子丑寅卯辰巳午未申酉戌亥",
            "あいうえおかきくけこさしすせそたちつてとなにぬねのはひふへほまみむめもやゆよらりるれろわをん", "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン",
            "가나다라마바사아자차카타파하", "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ", "אבגדהוזחטיכלמנסעפצקרשת", "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"))
            for (i in 0 until s.length - 1) { add("${s[i]}${s[i + 1]}"); add("${s[i + 1]}${s[i]}") }
    }

    /** The characters of [s] that add to a guess ([strength]). */
    private fun guessable(s: List<String>): List<String> {
        val c = s.fold(mutableListOf<String>()) { a, x -> if (a.lastOrNull() != x && a.size < 4096) a.add(x); a }
        // A shifted digit on a keyboard is that digit's key.
        val key = { x: String -> "!@#$%^&*()".indexOf(x).takeIf { x.length == 1 && it >= 0 }?.let { "1234567890"[it].toString() } ?: x }
        val step = step@{ a: String, b: String ->
            if (digit(a) && digit(b)) return@step Math.abs(Character.digit(a.codePointAt(0), 10) - Character.digit(b.codePointAt(0), 10)) == 1
            if ("$a$b" in COUNTED) return@step true
            // Numbers of one kind next to each other in value, whatever their decoration (❶❷, ➀➁, Ⅰ Ⅱ).
            if (number(a) && number(b) && Character.getType(a.codePointAt(0)) == Character.getType(b.codePointAt(0)) &&
                Character.getNumericValue(a.codePointAt(0)).let { x -> x >= 0 && Math.abs(x - Character.getNumericValue(b.codePointAt(0))) == 1 }) return@step true
            // Neighbouring letters or numerals of one alphabet (αβγ, абв, Ⅰ Ⅱ Ⅲ), whatever the script.
            val (u, v) = a.codePointAt(0) to b.codePointAt(0)
            if (a.codePointCount(0, a.length) == 1 && b.codePointCount(0, b.length) == 1 && Math.abs(u - v) == 1 && letter(u) && letter(v)) return@step true
            val x = key(a); val y = key(b)
            x.length == 1 && y.length == 1 && x[0].code < 128 && y[0].code < 128 && x[0].isLetterOrDigit() && y[0].isLetterOrDigit() && (Math.abs(x[0] - y[0]) == 1 || "$x$y" in KEYBOARD)
        }
        // The longest stretch starting at [i] that already appeared before it.
        val seen = { i: Int -> (0 until i).filter { c[it] == c[i] }.maxOfOrNull { j -> var k = 0; while (i + k < c.size && j + k < i && c[j + k] == c[i + k]) k++; k } ?: 0 }
        val n = mutableListOf<String>()
        var i = 0
        while (i < c.size) {
            val l = seen(i)
            if (l >= 2) {
                i += l
                // A counter after a repeated word: "pass1pass2pass3".
                if (l >= 3) while (i < c.size && digit(c[i])) i++
                continue
            }
            val walk = (i >= 2 && step(c[i - 2], c[i - 1]) && step(c[i - 1], c[i])) || (i >= 4 && step(c[i - 4], c[i - 2]) && step(c[i - 2], c[i]))
            if ((i == 0 || c[i] != c[i - 1]) && !walk) n.add(c[i])
            i++
        }
        return n
    }

    // ---- what the file holds ----

    /** The plaintext: a format of its own, so the setup inside can change format with the app ([Setup.version]). */
    @Serializable
    data class Contents(
        val format: Int = CONTENTS, val app: String = "", val created: Long = 0, val setup: Setup,
        /** False: saved without own servers' keys and passwords and without the home WireGuard peer ([contents]). */
        val keys: Boolean = true,
    )

    const val CONTENTS = 1
    private const val WG_LABELS = 2
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * What a backup of [s] holds: routes, app choices, favourites, profiles, own servers, the home WireGuard peer,
     * preferences and provider page settings. Never sign-ins (they live outside [Setup]), nor anything of Tailscale
     * (its auth key, server and subnets belong to this phone's sign-in), nor the statistics consent. Without [keys], own
     * servers and the routes through them keep only what isn't secret ([withoutSecrets]), and the home peer is left out.
     */
    fun contents(s: Setup, keys: Boolean = true): Setup {
        val clean = s.tidyAppSigners()
        val c = clean.copy(home = Home(wg = s.home.wg), prefs = s.prefs.copy(stats = false), pristine = false,
            waitOverrides = emptyMap(), profiles = clean.profiles.map { it.copy(waitOverrides = emptyMap()) },
            waiting = emptySet(), waitingUntil = 0, waitingEach = emptyMap(), version = VERSION, testing = emptySet())
        if (keys) return if (c.routes.any { it.socks5 != null && c.servers.none { sv -> sv.id == it.own } }) linked(c, localOnly = true) else c
        val linked = linked(c)
        return linked.copy(home = Home(), servers = linked.servers.map { it.withoutSecrets() }, routes = linked.routes.map { it.withoutSecrets() })
    }

    /** [s] with every imported route tied to an own server, so a route saved without keys has one to get them back through. */
    private fun linked(s: Setup, localOnly: Boolean = false): Setup {
        var servers = s.servers
        val routes = s.routes.map { r ->
            if (localOnly && r.socks5 == null || !r.imported || r.tsExit != null || servers.any { it.id == r.own }) return@map r
            val o = servers.firstOrNull { it.wg == r.wg && it.ovpn == r.ovpn && it.ss == r.ss && it.socks5 == r.socks5 }
                ?: OwnServer(generateSequence { "s" + java.util.UUID.randomUUID().toString().take(8) }.first { id -> servers.none { it.id == id } }, r.wg, r.ovpn, r.ss, socks5 = r.socks5)
                    .also { servers = servers + it }
            r.copy(own = o.id)
        }
        return s.copy(routes = routes, servers = servers)
    }

    /** Whether [s] holds anything [contents] leaves out without keys: own servers, imported routes or a home peer. */
    fun hasSecrets(s: Setup) = s.servers.isNotEmpty() || s.home.wg != null || s.routes.any { it.imported && it.tsExit == null }

    fun export(s: Setup, app: String, now: Long, keys: Boolean = true): ByteArray {
        val setup = contents(s, keys).stamped
        val compact = setup.servers.any { it.keyless && it.wg != null } || setup.routes.any { it.keyless && it.wg != null }
        val saved = if (!compact) setup else setup.copy(
            servers = setup.servers.map { if (it.keyless && it.wg != null) it.copy(wg = null, wgIdentity = it.wg.identity()) else it },
            routes = setup.routes.map { if (it.keyless && it.wg != null) it.copy(wg = null, wgIdentity = it.wg.identity()) else it },
        )
        return json.encodeToString(Contents.serializer(), Contents(if (compact) WG_LABELS else CONTENTS, app, now, saved, keys)).toByteArray()
    }

    /** [plain] read and checked whole ([valid]); throws [Failed] (Newer, Invalid, OtherPlatform) and changes nothing. */
    fun read(plain: ByteArray): Contents {
        if (plain.size > MAX) fail(Reason.TooLarge)
        val text = String(plain, Charsets.UTF_8)
        val c = runCatching { json.decodeFromString(Contents.serializer(), text) }.getOrElse {
            // The Mac's backups hold their setup under "mac": the same container, another app's settings.
            if (runCatching { json.parseToJsonElement(text).let { it is JsonObject && "mac" in it && "setup" !in it } }.getOrDefault(false)) fail(Reason.OtherPlatform)
            fail(Reason.Invalid, "its contents can't be read")
        }
        if (c.format > WG_LABELS || c.setup.version > VERSION) fail(Reason.Newer)
        need(c.format >= CONTENTS, "its backup format is invalid")
        val setup = expandWgLabels(c)
        if (setup.servers.any { it.socks5 != null } || setup.routes.any { it.socks5 != null }) {
            need(setup.version >= 7, "a local proxy needs the newer setup format")
        }
        if (setup.servers.any { it.socks5?.allowUdp == true } || setup.routes.any { it.socks5?.allowUdp == true }) {
            need(setup.version >= 8, "UDP consent needs the newer setup format")
        }
        if (!c.keys) {
            need(setup.servers.none { it.socks5 != null && !it.keyless } && setup.routes.none { it.socks5 != null && !it.keyless },
                "a backup without keys holds a local proxy config")
        }
        val checked = valid(shown(named(setup.migrated())))
        return c.copy(app = c.app.takeIf { APP_VERSION.matches(it) }.orEmpty(), setup =
            if (c.format == CONTENTS) checkLegacyWgFields(checked, text) else checked)
    }

    /** The characters people see that a text from a file that is only shown keeps: no place or server a provider lists comes near it. */
    const val SHOWN = 64

    /**
     * [s] with every text from a file that is only shown cleaned ([safe], at most [SHOWN]), so the app, its widgets and
     * its notification never show what an older version let through: a pinned server's city and country, and Tailscale
     * nodes' names. Texts that pick or match something stay exactly as the file has them, whatever a provider's list
     * holds: a place's country, code and city and a pin's name (a location's identity, its favourite and its provider's
     * lookup), own servers' names (a server saved without keys gets its config back by its name), a pin's host, region,
     * group and the Tailscale address. The two that also go into a provider's or the speed test's request are kept only
     * in a shape that can't carry a request, else left out: a country code of at most three letters, digits or placeholder dots (it also
     * matches a route to the provider's list, so it is otherwise kept as saved; the speed test checks it again), a group as an identifier.
     */
    /** Letters, digits and the dots shown for a country without a code: nothing a request could read as its own. */
    private val CODE = Regex("[\\p{L}\\p{N}·]{0,3}")
    fun shown(s: Setup) = s.copy(routes = s.routes.map(::shown), favorites = s.favorites.map(::shown))
    fun shown(r: Route) = r.copy(loc = shown(r.loc), tsName = r.tsName?.let { safe(it, SHOWN) })
    fun shown(l: Location) = l.copy(countryCode = l.countryCode?.takeIf(CODE::matches), group = l.group?.takeIf(ID::matches),
        pinned = l.pinned?.run { copy(city = safe(city, SHOWN), country = safe(country, SHOWN)) })

    /** [plain] read back as a restore would read it, so a file that restoring would refuse is never written. Throws [Failed]. */
    fun readBack(plain: ByteArray) {
        try { read(plain) } catch (e: Failed) { fail(Reason.Unrestorable, (e.detail ?: e.reason.text.replaceFirstChar(Char::lowercase)).removeSuffix(".")) }
    }

    /** The version that made a backup, as the restore sheet shows it: a short version string, or nothing. */
    private val APP_VERSION = Regex("[0-9][0-9A-Za-z.+-]{0,31}")

    /**
     * [s] as a backup may hold it: a pinned server by its name and host only. Every provider looks a pin up in its own
     * list again and takes the saved address and key only while that list can't be read, so a backup must never be what
     * names the server a provider's tunnel is made to. Nor may it choose a provider's blocking resolvers: IVPN's block
     * list is saved as a pair of addresses, so it starts again from the standard one.
     */
    fun named(s: Setup): Setup = s.copy(routes = s.routes.map { it.copy(loc = bare(it.loc)) }, favorites = s.favorites.map(::bare),
        opts = s.opts.mapValues { (p, o) -> if (p == "ivpn") o - "list" else o })

    /** [l] with its pin kept by name and host ([named]). */
    fun bare(l: Location) = l.copy(pinned = l.pinned?.let { Server(it.name, it.host, "", "", -1, it.city, it.country) })

    private val ID = Regex("[A-Za-z0-9._-]{1,64}")
    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")

    /** Why a file can't be restored; a name from the file in it is shown as [safe] makes it. */
    private fun bad(what: String): Nothing = fail(Reason.Invalid, safe(what, 300))
    private fun need(ok: Boolean, what: String) { if (!ok) bad(what) }

    /** Formatting characters that only join or keep apart the letters around them, as in emoji and some scripts. */
    private fun joiner(c: Char) = c == '‌' || c == '‍'
    private fun format(c: Char) = Character.getType(c).let { it == Character.FORMAT.toInt() || it == Character.LINE_SEPARATOR.toInt() || it == Character.PARAGRAPH_SEPARATOR.toInt() } && !joiner(c)

    /** Text from a file as the app shows it: no control, direction or other invisible formatting characters, at most [n] characters. */
    fun safe(s: String, n: Int = 40) = s.filterNot { it.isISOControl() || format(it) }.takeGraphemes(n)

    /** A name from the file as the restore sheet prints it, every name it prints: [safe], trimmed, and [or] when nothing visible is left. */
    fun label(name: String?, or: String, n: Int = 60) = name?.let { safe(it, n).trim() }?.takeIf { it.codePoints().anyMatch { c -> !blank(c) } } ?: or

    /** Characters that show nothing on their own: spaces, joiners and the other default-ignorable ones (fillers, variation selectors, tags). */
    private fun blank(c: Int) = Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.getType(c) == Character.FORMAT.toInt() || c == 0x34F ||
        c in 0x115F..0x1160 || c in 0x17B4..0x17B5 || c in 0x180B..0x180F || c == 0x3164 || c in 0xFE00..0xFE0F || c == 0xFFA0 || c in 0xE0000..0xE0FFF

    /**
     * A name as the importers leave it, the rule every earlier version checked: no control or direction characters, not
     * padded. Other invisible formatting characters pass, as the importers keep them, so a backup an older version wrote
     * always opens; wherever a name from the file is shown, [safe] takes them out. It stays as it is when stored: a server
     * saved without keys gets its config back from this phone's server of the same name.
     */
    private fun plainName(n: String) = n.isNotBlank() && n.length <= 256 && Manual.cleanName(n, 256) == n

    /** A Tailscale exit node as unbraid saves it: its tailnet address, IPv4 or IPv6. */
    private fun tsAddress(a: String) = a == a.trim() && (app.unbraid.vpn.Compiler.ipv4(a)?.second == 32 && '/' !in a || WgConf.ipv6(a))

    /** A website as unbraid keeps it: as [Sites.clean] writes it, or as an older version saved it (labels of letters, digits and hyphens, the last not all digits). */
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
    private fun savedSite(k: String) = Sites.clean(k) == k || k.split('.').let { l -> k.length <= 253 && l.size >= 2 && l.all(LABEL::matches) && !l.last().all(Char::isDigit) }

    /**
     * [s] if every part of it is as unbraid itself would save it: own servers pass the same checks as an import, every
     * reference names something that exists, profiles follow their rules, and the engine can build it. Throws [Failed].
     */
    fun valid(s: Setup): Setup {
        // As many app choices as the file holds: the restore sheet checks as many as it can ([MAX_APPS]) and says so.
        need(s.routes.size <= MAX_EACH && s.servers.size <= MAX_EACH && s.favorites.size <= 2000 && s.profiles.size <= MAX_PROFILES, "it holds more than unbraid keeps")
        val routeIds = s.routes.mapTo(HashSet()) { it.id }
        val serverById = s.servers.associateBy { it.id }
        need(s.routes.all { ID.matches(it.id) && !builtIn(it.id) } && s.routes.map { it.id }.toSet().size == s.routes.size, "a route has a missing or repeated name")
        need(s.servers.all { ID.matches(it.id) } && s.servers.map { it.id }.toSet().size == s.servers.size, "an own server has a missing or repeated name")
        s.servers.forEach { sv ->
            need(sv.wgIdentity == null && listOfNotNull(sv.wg, sv.ovpn, sv.ss, sv.socks5).size == 1, "an own server has no configuration")
            if (sv.keyless) keyless(sv.name, sv.wg, sv.ovpn, sv.ss, sv.socks5) else server(sv.name, sv.wg, sv.ovpn, sv.ss, sv.socks5)
        }
        s.routes.forEach { r ->
            val n = listOfNotNull(r.wg, r.ovpn, r.ss, r.socks5, r.tsExit).size
            need(r.wgIdentity == null && n <= 1, "a route has more than one exit")
            if (n == 0) need(Providers.get(r.loc.provider) != null, "a route uses a provider this unbraid doesn't know")
            // Its node's name and its place are never refused, as older versions kept them unchecked; what is only shown is cleaned ([shown]).
            r.tsExit?.let { need(tsAddress(it), "a Tailscale route is damaged") }
            // A route saved without keys gets them back only through its own server, which must be saved without them too.
            val own = r.own?.let(serverById::get)
            need(r.keyless == (own?.keyless == true) && (!r.keyless || (n == 1 && r.tsExit == null)), "a route and its own server don't match")
            if (r.socks5 != null || own?.socks5 != null) need(own != null && own.socks5 == r.socks5, "a local proxy and its route do not match")
            if (r.keyless) keyless(r.name, r.wg, r.ovpn, r.ss, r.socks5) else server(r.name, r.wg, r.ovpn, r.ss, r.socks5)
        }
        need(s.supportsSocks5Assignments(), "a local proxy can't be used for a website or with Via")
        s.home.wg?.let { server(it.name, it, null, null, null) }
        // An app or a website may be on Block; a default route never is.
        val ok = { id: String -> builtIn(id) || id in routeIds }
        val pkgs = { m: Map<String, String> -> m.keys.all { it.length <= 255 && PKG.matches(it) } }
        // A website an older version kept stays as this phone keeps it: one name the browser can't open never costs the whole backup.
        val sites = { m: Map<String, String> -> m.size <= Sites.MAX && m.all { (site, id) -> savedSite(site) && ok(id) } }
        need(s.defaultRoute != BLOCK && s.profiles.none { it.defaultRoute == BLOCK }, "a default route can't be Block")
        need(s.defaultRoute.let(ok) && s.apps.values.all(ok) && s.added.all(ok) && pkgs(s.apps), "an app or the default route names a route that isn't there")
        // The filter's own choices per app are app choices too: as many at most, and package names.
        need(s.prefs.filter.apps.keys.all { it.length <= 255 && PKG.matches(it) }, "the Protection filter's app choices are damaged")
        val fingerprint = Regex("[0-9a-f]{64}")
        fun validPins(pins: Map<String, String>, rules: Map<String, String>) = pins.all { (pkg, pin) ->
            pkg in rules && rules[pkg] != BLOCK && pkg.length <= 255 && PKG.matches(pkg) && pin.length <= 64 * 65 && pin.split(',').let { certs ->
                certs.all(fingerprint::matches) && certs == certs.distinct().sorted()
            }
        }
        need(validPins(s.appSigners, s.apps) && s.profiles.all { validPins(it.appSigners, it.apps) }, "an app signing identity is damaged")
        need(sites(s.sites), "a website's route is damaged")
        need(s.onNet.all { (kind, name) -> kind in NET_KINDS && s.profiles.any { it.name == name } }, "a profile to switch to isn't there")
        s.profiles.forEach { p ->
            need(s.copy(profiles = s.profiles - p).profileName(p.name) == p.name, "a profile has a name unbraid can't use")
            need((p.defaultRoute.isEmpty() || ok(p.defaultRoute)) && p.apps.values.all(ok) && p.added.all(ok) && pkgs(p.apps) && sites(p.sites), "profile ${p.name} names a route that isn't there")
        }
        need(s.profile == null || s.profiles.any { it.name == s.profile && it.ready }, "the profile in use is missing")
        need(s.prefs.filter.id == null || s.prefs.filter.server() != null, "the Protection filter is damaged")
        need(s.opts.size <= 64 && s.opts.values.all { o -> o.size <= 64 && o.all { (k, v) -> k.length <= 64 && v.length <= 256 } }, "a provider page setting is damaged")
        // Built as the engine would get it with no provider signed in: own servers get their tunnels, the rest is blocked.
        // Every ready profile too: one that can't run would only fail once the user switches to it.
        for ((name, v) in listOf<Pair<String?, Setup>>(null to s) + s.profiles.filter { it.ready && it.name != s.profile }.map { it.name to s.viewAs(it.name) }) runCatching {
            val p = app.unbraid.vpn.LinkPicker.pick(v, emptyMap(), null, emptyMap(), emptyMap(), emptyMap(), offline = true) { Providers.get(it) }
            app.unbraid.vpn.Compiler.compile(app.unbraid.vpn.Compiler.Input(v, p.links, "app.unbraid", false, "", "", blocked = p.blocked.keys))
        }.onFailure { bad("${name?.let { "profile $it" } ?: "its setup"} can't run (${it.message ?: "unknown reason"})") }
        // A route copied from an own server that is gone keeps its config, no longer tied to it.
        return s.copy(routes = s.routes.map { r -> if (r.own != null && s.servers.none { it.id == r.own }) r.copy(own = null) else r })
    }

    /**
     * Own servers and routes of their own in [s] that an import would warn about ([Manual.weakness]), unless [accepted]
     * (this phone, which accepted them at their import) has the very same config: "name: what is weak", once each.
     */
    fun weak(s: Setup, accepted: Setup): List<String> {
        val known = (accepted.servers.flatMap { listOf(it.ovpn, it.ss) } + accepted.routes.flatMap { listOf(it.ovpn, it.ss) }).filterNotNullTo(HashSet())
        return (s.servers.filter { !it.keyless }.map { Triple(it.name, it.ovpn, it.ss) } + s.routes.filter { !it.keyless }.map { Triple(it.name, it.ovpn, it.ss) }).mapNotNull { (name, o, x) ->
            val why = o?.takeIf { it !in known }?.let { runCatching { Manual.weakness(it) }.getOrNull() } ?: x?.takeIf { it !in known }?.let(Manual::weakness) ?: return@mapNotNull null
            // The import's first sentence says what is weak; the sheet says what it risks once for all.
            "${label(name, "An own server")}: " + why.substringBefore(". ").removeSuffix(".").removePrefix("This config ")
        }.distinct()
    }

    /** A server saved without keys holds nothing secret, and the rest of it is as an import would leave it. */
    private fun keyless(name: String, wg: WgPeer?, ovpn: OvpnProfile?, ss: SsProfile?, socks5: Socks5Profile?) {
        val ok = plainName(name) && when {
            wg != null -> wg.privateKey.isEmpty() && wg.presharedKey == null && wg.endpoint.length <= 300 && wg.publicKey.length <= 64 &&
                wg.address.size <= 16 && wg.allowedIps.size <= 256 && wg.dns.size <= 16 && (wg.address + wg.allowedIps + wg.dns).all { it.length <= 64 && it.none(Char::isISOControl) }
            ovpn != null -> ovpn == OvpnProfile(ovpn.name, "")
            socks5 != null -> socks5.username.isEmpty() && socks5.password.isEmpty() && socks5.needsConfirmation && runCatching { socks5.checked() }.isSuccess
            ss != null -> ss.password.isEmpty() && ss.pluginOpts == null && ss.server.length <= 253 && ss.method.length <= 64 && ss.port in 1..65535 &&
                (ss.plugin?.length ?: 0) <= 64 && (ss.server + ss.method + ss.plugin.orEmpty()).none(Char::isISOControl)
            else -> false
        }
        need(ok, "own server ${label(name, "with no visible name", 40)} is damaged")
    }

    /** Checks an own server's config as an import would; its reason names the server, never a key or a password. */
    private fun server(name: String, wg: WgPeer?, ovpn: OvpnProfile?, ss: SsProfile?, socks5: Socks5Profile?) {
        val why = runCatching {
            wg?.let { require(plainName(it.name) && WgConf.parse(WgConf.text(it), it.name) == it) { "its WireGuard settings are damaged" } }
            ovpn?.let { require(plainName(it.name) && it.config.length <= 256 * 1024 && it.username.length <= 1024 && it.password.length <= 1024) { "it is damaged" }; Manual.parseOvpn(it.config, it.name, it.username, it.password) }
            socks5?.checked()
            ss?.let { require(plainName(it.name) && Manual.parseSs(Manual.ssLink(it), it.name) == it) { "its Shadowsocks settings are damaged" } }
        }.exceptionOrNull() ?: return
        bad("own server ${label(name, "with no visible name", 40)}:${(why.message ?: "it is damaged").replaceFirstChar(Char::lowercase).removeSuffix(".")}")
    }
}

/** Refills a waiting server and all of its route copies. Editing a confirmed proxy also requires the exact record shown. */
fun Setup.refilled(id: String, server: OwnServer, expectedProxy: OwnServer? = null): Setup? {
    val saved = servers.firstOrNull { it.id == id } ?: return null
    if (expectedProxy != null && (saved.socks5 == null || saved != expectedProxy)) return null
    if (!saved.keyless && saved.socks5?.needsConfirmation != true && expectedProxy == null) return null
    if (saved.socks5 != null || server.socks5 != null) {
        if (expectedProxy == null) return null
        val confirmed = server.socks5 ?: return null
        val previous = saved.socks5 ?: return null
        if (confirmed.needsConfirmation || (saved.keyless || previous.needsConfirmation) && previous.authRequired && !confirmed.authRequired || runCatching { confirmed.checked() }.isFailure) return null
    }
    // UDP is allowed on the server's own page only: an edit keeps it for the same app, port and login, and never turns it on.
    val proxy = server.socks5?.let { next ->
        val old = saved.socks5
        next.copy(allowUdp = old != null && !saved.keyless && !old.needsConfirmation && old.allowUdp &&
            old.packageName == next.packageName && old.identity == next.identity && old.port == next.port &&
            old.username == next.username && old.password == next.password)
    }
    val sv = server.copy(id = id, keyless = false, socks5 = proxy)
    return copy(servers = servers.map { if (it.id == id) sv else it },
        routes = routes.map { r -> if (r.own != id) r else r.copy(wg = sv.wg, ovpn = sv.ovpn, ss = sv.ss, socks5 = sv.socks5, keyless = false) })
}

/** Changes UDP on the saved proxy and all its route copies. */
fun Setup.setProxyUdp(expected: OwnServer, on: Boolean): Setup {
    val saved = servers.firstOrNull { it.id == expected.id } ?: return this
    val proxy = saved.socks5 ?: return this
    if (on && (saved != expected || saved.keyless || proxy.needsConfirmation)) return this
    val changed = proxy.copy(allowUdp = on)
    return copy(servers = servers.map { if (it.id == saved.id) it.copy(socks5 = changed) else it },
        routes = routes.map { if (it.own == saved.id) it.copy(socks5 = changed) else it })
}

/** Apps restored but not on this phone keep their route this long, to be installed meanwhile. */
const val WAIT_MS = 30 * 86_400_000L

/** An already active choice when another profile is still waiting for the same package. */
private const val ACTIVE_CHOICE = Long.MIN_VALUE

internal fun Setup.deadline(p: Profile, pkg: String): Long? {
    if (pkg !in waiting) return null
    val route = p.apps[pkg] ?: return null
    return (p.waitOverrides[pkg] ?: waitingEach[pkg]?.get(route) ?: waitingUntil).takeUnless { it == ACTIVE_CHOICE }
}

private data class PendingProfile(val profile: Profile, val due: Map<String, Long>)

private fun Setup.pending(p: Profile) = PendingProfile(p, p.apps.keys.mapNotNull { pkg -> deadline(p, pkg)?.let { pkg to it } }.toMap())

/** Prefer the original global and per-route encoding. Only differing choices in separate profiles need an override. */
private fun Setup.withWaits(active: PendingProfile, saved: List<PendingProfile>): Setup {
    val byPackage = mutableMapOf<String, MutableMap<String, Long>>()
    for ((profile, due) in listOf(active) + saved) for ((pkg, date) in due) {
        val route = profile.apps[pkg] ?: continue
        byPackage.getOrPut(pkg) { mutableMapOf() }.merge(route, date, ::minOf)
    }
    val until = (listOf(active) + saved).maxOf { it.due.values.maxOrNull() ?: 0 }
    val each = byPackage.mapValues { (_, dates) -> dates.filterValues { it != until } }.filterValues { it.isNotEmpty() }
    fun encoded(row: PendingProfile): Profile {
        val overrides = row.profile.apps.mapNotNull { (pkg, route) ->
            val fallback = byPackage[pkg]?.get(route) ?: until.takeIf { pkg in byPackage }
            val date = row.due[pkg]
            if (date == fallback) null else pkg to (date ?: ACTIVE_CHOICE)
        }.toMap()
        return row.profile.copy(waitOverrides = overrides)
    }
    val live = encoded(active)
    return copy(apps = live.apps, appSigners = live.appSigners, waitOverrides = live.waitOverrides, profiles = saved.map(::encoded),
        waiting = byPackage.keys.toSet(), waitingUntil = until, waitingEach = each)
}

private enum class WaitDecision { Active, Pending, Expired }

private fun InstallState.decision(due: Long, now: Long, expected: String?): WaitDecision {
    if (this == InstallState.Absent) return if (now >= due) WaitDecision.Expired else WaitDecision.Pending
    val present = this as? InstallState.Present ?: return WaitDecision.Pending
    val observed = maxOf(now, present.observedAt ?: now)
    val first = present.firstInstallTime?.takeIf { it in 1..observed }
    if (first != null && first >= due) return WaitDecision.Expired
    val authorized = expected == null || present.identity?.accepts(expected) == true
    return if (authorized && (first != null || observed < due)) WaitDecision.Active else WaitDecision.Pending
}

/** Recheck a single grant right at Android's owner or TUN boundary when a saved restore is still pending. */
internal fun Setup.waitAdmits(p: Profile, pkg: String, now: Long, state: InstallState): Boolean =
    deadline(p, pkg)?.let { state.decision(it, now, p.appSigners[pkg]) == WaitDecision.Active } ?: true

/** Consume a proven timely installation, forget a proven late choice, retain unresolved provenance for a later retry. */
internal fun Setup.resolveWaiting(now: Long, installed: (String) -> InstallState, effective: Boolean = false): Setup {
    if (waiting.isEmpty()) return this
    val observations = HashMap<String, InstallState>()
    val expiredDirect = mutableSetOf<String>()
    val remainingDirect = mutableSetOf<String>()
    fun resolve(p: Profile): PendingProfile {
        val remaining = mutableMapOf<String, Long>()
        val apps = p.apps.toMutableMap()
        val pins = p.appSigners.toMutableMap()
        for (pkg in p.apps.keys) {
            val due = deadline(p, pkg)
            if (due == null) {
                if (p.apps[pkg] == DIRECT) remainingDirect += pkg
                continue
            }
            val state = observations.getOrPut(pkg) { installed(pkg) }
            val decision = state.decision(due, now, p.appSigners[pkg].takeUnless { p.apps[pkg] == BLOCK })
            if (decision == WaitDecision.Expired) {
                if (apps.remove(pkg) == DIRECT) expiredDirect += pkg
                continue
            }
            if (p.apps[pkg] == DIRECT) remainingDirect += pkg
            if (decision == WaitDecision.Active) {
                if (pkg in pins && p.apps[pkg] != BLOCK) (state as? InstallState.Present)?.identity?.let { pins[pkg] = it.current }
                continue
            }
            remaining[pkg] = due
            if (effective) apps[pkg] = BLOCK
        }
        return PendingProfile(p.copy(apps = apps, appSigners = pins.filterKeys(apps::containsKey)), remaining)
    }
    val result = withWaits(resolve(live), profiles.map(::resolve))
    val clear = expiredDirect - remainingDirect
    return if (clear.none(prefs.filter.apps::containsKey)) result
        else result.copy(prefs = prefs.copy(filter = prefs.filter.copy(apps = prefs.filter.apps - clear)))
}

/** A rejected profile switch must not resurrect a settled or expired wait from an earlier running snapshot. */
internal fun Setup.rollbackWaits(savedSetup: Setup, ran: Setup): Setup {
    if (savedSetup.waiting.isEmpty() && ran.waiting.isEmpty()) return this
    val previous = if (savedSetup.profile == ran.profile) savedSetup.live else savedSetup.saved.firstOrNull { it.name == ran.profile }
    val apps = apps.toMutableMap()
    val due = mutableMapOf<String, Long>()
    for ((pkg, route) in apps.toMap()) {
        val old = ran.deadline(ran.live, pkg)
        if (old != null && previous != null && pkg !in previous.apps) apps.remove(pkg)
        else {
            val whenDue = if (previous?.apps?.get(pkg) == route) savedSetup.deadline(previous, pkg) else old
            if (whenDue != null) due[pkg] = whenDue
        }
    }
    val active = PendingProfile(live.copy(apps = apps, appSigners = appSigners.filterKeys(apps::containsKey)), due)
    return withWaits(active, profiles.map { p -> if (p.name == profile) active.copy(profile = active.profile.copy(name = p.name)) else savedSetup.pending(p) })
}

/** Rechoosing an installed app cancels its restore wait only in the profile the user edited. */
internal fun Setup.clearAppWait(name: String?, pkg: String): Setup {
    if (pkg !in waiting) return this
    val target = name ?: profile
    fun row(p: Profile, selected: Boolean): PendingProfile = pending(p).let { if (selected) it.copy(due = it.due - pkg) else it }
    return withWaits(row(live, target == profile), profiles.map { row(it, it.name == target) })
}

/**
 * This phone's setup replaced by backup [b] ([Backup.valid]). Tailscale and the statistics consent stay as they are
 * here. Apps in it wait for [WAIT_MS] to be installed. With [keepAs] (and something set up here), the setup in use is
 * kept as a profile of that name (or that name and a number), with its routes and own servers: a route or server
 * whose name the backup uses for something else gets a new one. Null when there is no room for that profile, or the two
 * together hold more routes or own servers than a setup may ([Backup.MAX_EACH]).
 */
fun Setup.restoredFrom(backup: Setup, keepAs: String?, now: Long, keys: Boolean = true, installedAt: (String) -> Long? = { null }): Setup? =
    restoredWithInstalls(backup, keepAs, now, keys) { pkg -> installedAt(pkg)?.let { InstallState.Present(it) } ?: InstallState.Absent }

internal fun Setup.restoredWithInstalls(backup: Setup, keepAs: String?, now: Long, keys: Boolean = true,
                                        installed: (String) -> InstallState): Setup? {
    val snapshot = HashMap<String, InstallState>()
    val read = { pkg: String -> snapshot.getOrPut(pkg) { installed(pkg) } }
    val kept = resolveWaiting(now, read)
    return kept.mergeBackup(backup, keepAs, now, keys)?.resolveWaiting(now, read)
}

private fun Setup.mergeBackup(source: Setup, keepAs: String?, now: Long, keys: Boolean): Setup? {
    if (!source.supportsSocks5Assignments()) return null
    // Even a backup that contains credentials cannot confirm who owns a listener on this phone.
    val backup = source.copy(
        servers = source.servers.map { s -> s.socks5?.let { s.copy(socks5 = it.copy(needsConfirmation = true, allowUdp = false)) } ?: s },
        routes = source.routes.map { r -> r.socks5?.let { r.copy(socks5 = it.copy(needsConfirmation = true, allowUdp = false)) } ?: r },
    )
    // A compact WireGuard label cannot establish which tunnel settings to use. Older files carry the
    // settings on both the server and its route copies. Only a consistent, exact local match can refill them.
    val byOwn = backup.routes.groupBy { it.own }
    val full = backup.servers.filter { it.keyless && it.socks5 == null }.mapNotNull { k ->
        if (k.wg?.let { it.address.isEmpty() || it.allowedIps.isEmpty() } == true) return@mapNotNull null
        val copies = byOwn[k.id].orEmpty()
        if (copies.any { it.wg != k.wg }) return@mapNotNull null
        val saved = servers.firstOrNull { !it.keyless && it.id == k.id && k.sameAs(it) }
        val match = saved ?: copies.asSequence().mapNotNull { x ->
            route(x.id)?.takeIf { it.own == null && it.imported && it.tsExit == null && !it.keyless }
                ?.let { OwnServer(k.id, it.wg, it.ovpn, it.ss, socks5 = it.socks5) }?.takeIf { k.ovpn == null && k.sameAs(it) }
        }.let { candidates -> if (k.wg == null) candidates.firstOrNull() else candidates.distinct().singleOrNull() }
        match?.let { k.id to it }
    }.toMap()
    val b = if (full.isEmpty()) backup else backup.copy(
        servers = backup.servers.map { full[it.id] ?: it },
        routes = backup.routes.map { x -> full[x.own]?.takeIf { x.keyless }?.let { x.copy(wg = it.wg, ovpn = it.ovpn, ss = it.ss, keyless = false) } ?: x },
    )
    val pkgs = b.apps.keys + b.profiles.flatMap { it.apps.keys }
    var r = b.copy(home = home.copy(wg = if (keys) b.home.wg else home.wg), prefs = b.prefs.copy(stats = prefs.stats), pristine = false, version = VERSION,
        waiting = pkgs, waitingUntil = if (pkgs.isEmpty()) 0 else now + WAIT_MS, waitingEach = emptyMap(),
        waitOverrides = emptyMap(), profiles = b.profiles.map { it.copy(waitOverrides = emptyMap()) }, testing = emptySet())
    if (keepAs == null || untouched) return r
    if (r.profiles.size >= MAX_PROFILES) return null
    val name = (listOf(keepAs) + (2..MAX_PROFILES + 1).map { "$keepAs $it" }).firstNotNullOfOrNull { r.profileName(it) } ?: return null
    val fresh = { prefix: String, taken: Set<String> -> generateSequence { prefix + java.util.UUID.randomUUID().toString().take(8) }.first { it !in taken } }
    val here = routes.mapTo(HashSet()) { it.id }
    val ok = { id: String -> builtIn(id) || id in here }
    val apps = apps.filterValues(ok)
    val sites = sites.filterValues(ok)
    // Without profiles every route here shows, those no app uses too: all of them are kept, as saving a first profile does.
    val listed = if (profiles.isEmpty()) (added + (routes.map { it.id } - (setOf(defaultRoute) + apps.values + sites.values + added))).distinct() else added
    // A kept route's Via entry is kept with it, entries first: a Via route then points at the entry's id as kept.
    val used = (setOf(defaultRoute) + apps.values + sites.values + listed).mapNotNull(::route)
        .let { u -> (u + u.mapNotNull { route(it.via) }).distinct().sortedBy { it.via != null } }
    val serverIds = r.servers.map { it.id }.toMutableSet()
    val sv = HashMap<String, String>()
    // Every own server here stays, one no route uses too: keeping replaces only profiles, favourites and settings.
    val addServers = servers.mapNotNull { s ->
        val there = r.servers.firstOrNull { it.id == s.id }
        val id = if (there == null) s.id else if (there == s) { sv[s.id] = s.id; return@mapNotNull null } else fresh("s", serverIds)
        sv[s.id] = id; serverIds += id; s.copy(id = id)
    }
    val routeIds = r.routes.map { it.id }.toMutableSet()
    val rt = HashMap<String, String>()
    val pins = HashMap<String, Location>()
    val addRoutes = used.mapNotNull { x ->
        // An entry gone here gets an id nothing has, so the route stays blocked instead of going through the backup's route of that id.
        val c = x.copy(own = x.own?.let { sv[it] ?: it }, via = x.via?.let { v -> rt[v] ?: fresh("r", routeIds).also { routeIds += it; rt[v] = it } })
        val there = r.route(x.id)
        // The same route as the file holds it ([Backup.named], [Backup.shown]); one tied here to no own server is the same as the file's tied one.
        val same = there != null && Backup.shown(c.copy(loc = Backup.bare(c.loc))).let { f -> there == f || (f.own == null && there.copy(own = null) == f) }
        // Shared, it keeps the pin this phone trusts (address and key from the provider's list), not the file's name alone.
        if (same) { rt[x.id] = x.id; Backup.shown(c.loc).let { if (it != there!!.loc) pins[x.id] = it }; return@mapNotNull null }
        val id = if (there == null) x.id else fresh("r", routeIds)
        rt[x.id] = id; routeIds += id; c.copy(id = id)
    }
    // More than a setup may hold would restore, then fail every backup of it: counted as a backup without keys holds it,
    // with an own server made for every imported route that has none.
    if (Backup.contents(r.copy(routes = r.routes + addRoutes, servers = r.servers + addServers), keys = false).let { it.routes.size > Backup.MAX_EACH || it.servers.size > Backup.MAX_EACH }) return null
    if (pins.isNotEmpty()) r = r.copy(routes = r.routes.map { x -> pins[x.id]?.let { x.copy(loc = it) } ?: x })
    val m = { id: String -> rt[id] ?: id }
    // The first profile: every route shown until now stays listed, as when the first profile is saved ([Setup.saveAs]).
    if (r.profiles.isEmpty()) r = r.copy(added = (r.added + (r.routes.map { it.id } - (setOf(r.defaultRoute) + r.apps.values + r.sites.values + r.added))).distinct())
    val kept = Profile(name, if (ok(defaultRoute) && defaultRoute.isNotEmpty()) m(defaultRoute) else "", apps.mapValues { m(it.value) }, listed.filter(ok).map(m), sites.mapValues { m(it.value) }, appSigners.filterKeys { it in apps })
    // The old profile keeps its own cutoff even when the new backup names the same package, route and signer.
    val prior = PendingProfile(kept, pending(live).due.filterKeys { it in apps })
    val merged = r.copy(routes = r.routes + addRoutes, servers = r.servers + addServers)
        .withWaits(r.pending(r.live), r.profiles.map(r::pending) + prior)
    // Kept, it must still fit a backup with keys: large OpenVPN configs of both setups together could pass the counts but not the size.
    if (runCatching { Backup.export(merged, "", now, true).size + 1024 > Backup.MAX }.getOrDefault(false)) return null
    return merged
}
