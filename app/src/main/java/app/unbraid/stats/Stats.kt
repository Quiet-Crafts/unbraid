package app.unbraid.stats

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.sqlite.transaction
import app.unbraid.Unbraid
import app.unbraid.data.DIRECT
import app.unbraid.vpn.Compiler
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState.Phase
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local traffic statistics. Off until the user agrees; then the engine's per-connection byte counts
 * are summed per hour, day, app and exit into an app-private SQLite file. Never uploaded; no destinations kept.
 *
 * One ordered worker thread owns all state and every write: engine callbacks, lifecycle (start, stop, delete) and
 * sessions are queued to it, so nothing needs a lock, and a callback from a stopped or deleted recording (an older
 * [gen]) is dropped. Reads run on the caller's IO thread.
 */
@SuppressLint("StaticFieldLeak") // only the application context is held, which lives as long as the process
object Stats {
    private const val FILE = "stats.db"
    const val HOME = "home"
    private const val HOUR = 3_600_000L
    private const val TESTS_KEPT = 100

    @SuppressLint("StaticFieldLeak")
    internal lateinit var ctx: Context
    private var helper: Helper? = null
    private val db: SQLiteDatabase get() = (helper ?: Helper(ctx).also { helper = it }).writableDatabase
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "stats").apply { isDaemon = true } }

    /** Runs [f] on the worker; failures are logged, never thrown into the app. */
    internal fun post(f: () -> Unit) = worker.execute { runCatching(f).onFailure { Log.w("Unbraid", "stats: ${it.javaClass.simpleName}") } }

    fun init(context: Context) { ctx = context.applicationContext }

    /** Local hour and day numbers, so "today" and "Monday" match the phone's calendar. */
    fun hourOf(ms: Long) = (ms + TimeZone.getDefault().getOffset(ms)) / HOUR
    fun dayOf(ms: Long) = Math.floorDiv(hourOf(ms), 24L)

    private class Helper(c: Context) : SQLiteOpenHelper(c, FILE, null, 2) {
        override fun onCreate(d: SQLiteDatabase) = listOf(
            "CREATE TABLE hourly(h INTEGER, exit TEXT, up INTEGER, down INTEGER, PRIMARY KEY(h, exit)) WITHOUT ROWID",
            "CREATE TABLE daily(d INTEGER, app TEXT, exit TEXT, up INTEGER, down INTEGER, PRIMARY KEY(d, app, exit)) WITHOUT ROWID",
            "CREATE TABLE server(d INTEGER, exit TEXT, name TEXT, bytes INTEGER, PRIMARY KEY(d, exit, name)) WITHOUT ROWID",
            "CREATE TABLE peak(d INTEGER, exit TEXT, bps INTEGER, at INTEGER, up INTEGER NOT NULL DEFAULT 0, up_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(d, exit)) WITHOUT ROWID",
            "CREATE TABLE span(start INTEGER PRIMARY KEY, end INTEGER, paused INTEGER)",
            "CREATE TABLE exit(id TEXT PRIMARY KEY, code TEXT, name TEXT, hue INTEGER, via TEXT)",
            "CREATE TABLE test(at INTEGER PRIMARY KEY, exit TEXT, via TEXT, server TEXT, ip TEXT, down INTEGER, up INTEGER, idle INTEGER, busy_down INTEGER, busy_up INTEGER)",
        ).forEach(d::execSQL)

        // 2: peaks keep upload apart from download (bps/at stay the download's).
        override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
            if (old < 2) listOf("ALTER TABLE peak ADD COLUMN up INTEGER NOT NULL DEFAULT 0", "ALTER TABLE peak ADD COLUMN up_at INTEGER NOT NULL DEFAULT 0").forEach(d::execSQL)
        }
    }

    // ---- worker-owned state ------------------------------------------------------------------------------------

    /** Bumped by every new or stopped subscription: callbacks carrying an older value are dropped. */
    @Volatile internal var gen = 0
    private val recording get() = Unbraid.store.setup.value.prefs.stats

    private class Sum(var up: Long = 0, var down: Long = 0)

    private val hourly = HashMap<Pair<Long, String>, Sum>()
    private val daily = HashMap<Triple<Long, String, String>, Sum>()
    private val servers = HashMap<Triple<Long, String, String>, Long>()
    /** The busiest second per day and exit, download and upload apart (bits/s and when). */
    data class Peak(val down: Long, val downAt: Long, val up: Long, val upAt: Long)
    private val peaks = HashMap<Pair<Long, String>, Peak>()

    internal fun add(now: Long, app: String, exit: String, server: String?, up: Long, down: Long) {
        if (up <= 0 && down <= 0) return
        hourly.getOrPut(hourOf(now) to exit, ::Sum).let { it.up += up; it.down += down }
        val d = dayOf(now)
        daily.getOrPut(Triple(d, app, exit), ::Sum).let { it.up += up; it.down += down }
        if (server != null) servers.merge(Triple(d, exit, server), up + down, Long::plus)
    }

    internal fun peak(now: Long, exit: String, down: Long, up: Long) {
        val k = dayOf(now) to exit
        val p = peaks[k] ?: Peak(0, 0, 0, 0)
        if (down > p.down || up > p.up) peaks[k] = Peak(maxOf(down, p.down), if (down > p.down) now else p.downAt, maxOf(up, p.up), if (up > p.up) now else p.upAt)
    }

    /** Worker only: pending sums and the exits' labels in one transaction (explicit conflict targets: API 33's SQLite). */
    internal fun flushNow(force: Boolean = false) {
        if ((!recording && !force) || (hourly.isEmpty() && daily.isEmpty() && peaks.isEmpty())) return
        val d = db
        d.transaction {
            hourly.forEach { (k, s) -> d.execSQL("INSERT INTO hourly VALUES(?,?,?,?) ON CONFLICT(h, exit) DO UPDATE SET up=up+excluded.up, down=down+excluded.down", arrayOf<Any>(k.first, k.second, s.up, s.down)) }
            daily.forEach { (k, s) -> d.execSQL("INSERT INTO daily VALUES(?,?,?,?,?) ON CONFLICT(d, app, exit) DO UPDATE SET up=up+excluded.up, down=down+excluded.down", arrayOf<Any>(k.first, k.second, k.third, s.up, s.down)) }
            servers.forEach { (k, b) -> d.execSQL("INSERT INTO server VALUES(?,?,?,?) ON CONFLICT(d, exit, name) DO UPDATE SET bytes=bytes+excluded.bytes", arrayOf<Any>(k.first, k.second, k.third, b)) }
            peaks.forEach { (k, p) ->
                d.execSQL("INSERT INTO peak VALUES(?,?,?,?,?,?) ON CONFLICT(d, exit) DO UPDATE SET " +
                    "at=CASE WHEN excluded.bps > peak.bps THEN excluded.at ELSE peak.at END, bps=MAX(peak.bps, excluded.bps), " +
                    "up_at=CASE WHEN excluded.up > peak.up THEN excluded.up_at ELSE peak.up_at END, up=MAX(peak.up, excluded.up)",
                    arrayOf<Any>(k.first, k.second, p.down, p.downAt, p.up, p.upAt))
            }
            labelExits(d, daily.keys.map { it.third } + hourly.keys.map { it.second })
        }
        clearPending()
        maybePrune()
    }

    private var prunedDay = -1L
    /** Retention runs once a day whatever else happens: on writes and before reads. */
    private fun maybePrune() { val d = dayOf(System.currentTimeMillis()); if (d != prunedDay) { prunedDay = d; pruneNow() } }

    private fun clearPending() { hourly.clear(); daily.clear(); servers.clear(); peaks.clear() }

    /** Exits keep the label they had when used, so history survives renames and removed routes. */
    private fun labelExits(d: SQLiteDatabase, ids: List<String>) {
        val setup = Unbraid.store.setup.value
        ids.distinct().forEach { id ->
            val r = setup.route(id)
            val v = ContentValues()
            when {
                r != null -> { v.put("code", r.code); v.put("name", r.name); v.put("hue", r.hue); v.put("via", r.source) }
                id == HOME -> { v.put("code", "HM"); v.put("name", "Home network"); v.put("hue", 3); v.put("via", listOfNotNull("Tailscale".takeIf { setup.tailscale }, "WireGuard".takeIf { setup.home.wg != null }).joinToString(" & ").ifEmpty { "Tailscale" }) }
                id.startsWith(SpeedTest.NODE) -> { v.put("code", "TS"); v.put("name", UnbraidVpn.state.value.tsExitNodes.firstOrNull { SpeedTest.NODE + it.ip == id }?.name ?: id.removePrefix(SpeedTest.NODE)); v.put("hue", -1); v.put("via", "Tailscale") }
                else -> return@forEach
            }
            v.put("id", id)
            d.insertWithOnConflict("exit", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    /** Worker only: keeps what the consent text promises, whatever else happens. */
    internal fun pruneNow() {
        if (!ctx.getDatabasePath(FILE).exists()) return
        val now = System.currentTimeMillis()
        val today = dayOf(now)
        val d = db
        d.execSQL("DELETE FROM hourly WHERE h < ?", arrayOf<Any>((today - 8) * 24))
        listOf("daily", "server", "peak").forEach { d.execSQL("DELETE FROM $it WHERE d < ?", arrayOf<Any>(today - 400)) }
        d.execSQL("DELETE FROM span WHERE start < ?", arrayOf<Any>(now - 400 * 24 * HOUR))
        d.execSQL("DELETE FROM test WHERE at < ? OR at NOT IN (SELECT at FROM test ORDER BY at DESC LIMIT $TESTS_KEPT)", arrayOf<Any>(now - 400 * 24 * HOUR))
    }

    // ---- sessions: protected and paused spans, from the service state ------------------------------------------

    @Volatile private var open: Pair<Long, Boolean>? = null

    /** On every phase change (Off when stats are turned off): closes the running span, opens the next while recording. */
    fun phase(p: Phase) = post {
        val now = System.currentTimeMillis()
        open?.let { (start, paused) -> writeSpan(start, now, paused) }
        open = if (recording) when (p) { Phase.On -> now to false; Phase.Paused -> now to true; else -> null } else null
    }

    private fun writeSpan(start: Long, end: Long, paused: Boolean) =
        db.execSQL("INSERT OR REPLACE INTO span VALUES(?,?,?)", arrayOf<Any>(start, end, if (paused) 1 else 0))

    /** Worker only: keeps the open span's end current, so a killed process loses at most half a minute. */
    internal fun checkpointNow() { if (recording) open?.let { (s, p) -> writeSpan(s, System.currentTimeMillis(), p) } }

    // ---- lifecycle -------------------------------------------------------------------------------------------------

    fun start() = post { Collector.startNow(); pruneNow() }
    // Bytes accepted while recording are kept even if recording was just turned off.
    fun stop() = post { Collector.stopNow(); flushNow(force = true) }

    /** Bumped by every delete: a speed test started before one must not write its result after it. */
    @Volatile var epoch = 0
        private set

    /** Erases everything: the file and its journal. Recording, if on, carries on from now. */
    fun delete() = post {
        epoch++
        val wasOn = Collector.running
        Collector.stopNow()
        clearPending()
        open = open?.let { System.currentTimeMillis() to it.second }
        helper?.close(); helper = null
        ctx.deleteDatabase(FILE)
        if (wasOn && recording) Collector.startNow()
    }

    fun saveTest(t: Test, startedIn: Int) = post {
        if (!recording || startedIn != epoch) return@post
        db.execSQL("INSERT OR REPLACE INTO test VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any>(t.at, t.exit, t.via, t.server, t.ip, t.down, t.up, t.idle, t.busyDown, t.busyUp))
        labelExits(db, listOf(t.exit))
        pruneNow()
    }

    fun size(): Long = ctx.getDatabasePath(FILE).let { if (it.exists()) it.length() else 0L }

    /**
     * Runs a read on the worker after writing pending sums, so it sees the latest and never races a delete. Call off
     * the main thread (never from the worker itself).
     */
    fun <T> read(f: () -> T): T? { val t = FutureTask { runCatching { flushNow(); maybePrune(); f() }.getOrNull() }; worker.execute(t); return runCatching { t.get() }.getOrNull() }

    // ---- reading (IO thread) ---------------------------------------------------------------------------------------

    data class Exit(val id: String, val code: String, val name: String, val hue: Int, val via: String)
    data class Bytes(val up: Long, val down: Long) { val total get() = up + down }
    data class Test(val at: Long, val exit: String, val via: String, val server: String, val ip: String, val down: Long, val up: Long, val idle: Int, val busyDown: Int, val busyUp: Int)

    private inline fun <T> rows(sql: String, args: Array<String> = emptyArray(), read: (android.database.Cursor) -> T): List<T> =
        if (!ctx.getDatabasePath(FILE).exists()) emptyList()
        else runCatching { db.rawQuery(sql, args).use { c -> buildList { while (c.moveToNext()) add(read(c)) } } }.getOrDefault(emptyList())

    fun exits(): Map<String, Exit> = rows("SELECT id, code, name, hue, via FROM exit") { Exit(it.getString(0), it.getString(1), it.getString(2), it.getInt(3), it.getString(4)) }.associateBy { it.id }

    /** Bytes per exit per day, for days [from, to]. */
    fun perDay(from: Long, to: Long): Map<Long, Map<String, Bytes>> =
        rows("SELECT d, exit, SUM(up), SUM(down) FROM daily WHERE d BETWEEN ? AND ? GROUP BY d, exit", arrayOf("$from", "$to")) { Triple(it.getLong(0), it.getString(1), Bytes(it.getLong(2), it.getLong(3))) }
            .groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }

    /** Bytes per exit per hour of one day (the Day view). */
    fun perHour(day: Long): Map<Long, Map<String, Bytes>> =
        rows("SELECT h, exit, up, down FROM hourly WHERE h BETWEEN ? AND ?", arrayOf("${day * 24}", "${day * 24 + 23}")) { Triple(it.getLong(0) - day * 24, it.getString(1), Bytes(it.getLong(2), it.getLong(3))) }
            .groupBy({ it.first }, { it.second to it.third }).mapValues { it.value.toMap() }

    /** Bytes per app and exit over days [from, to], largest first. */
    fun apps(from: Long, to: Long) = rows("SELECT app, exit, SUM(up)+SUM(down) b FROM daily WHERE d BETWEEN ? AND ? GROUP BY app, exit ORDER BY b DESC", arrayOf("$from", "$to")) { Triple(it.getString(0), it.getString(1), it.getLong(2)) }

    /** Observed peaks per exit per day: download and upload, bits per second. */
    fun peaks(from: Long, to: Long) = rows("SELECT d, exit, bps, at, up, up_at FROM peak WHERE d BETWEEN ? AND ?", arrayOf("$from", "$to")) {
        Triple(it.getLong(0), it.getString(1), Peak(it.getLong(2), it.getLong(3), it.getLong(4), it.getLong(5)))
    }

    fun servers(exit: String, from: Long, to: Long) = rows("SELECT name, SUM(bytes) b FROM server WHERE exit = ? AND d BETWEEN ? AND ? GROUP BY name ORDER BY b DESC", arrayOf(exit, "$from", "$to")) { it.getString(0) to it.getLong(1) }

    /** Spans overlapping [from, to) ms: start, end, paused. The open span counts up to now. */
    fun spans(from: Long, to: Long): List<Triple<Long, Long, Boolean>> {
        val stored = rows("SELECT start, end, paused FROM span WHERE end > ? AND start < ? ORDER BY start", arrayOf("$from", "$to")) { Triple(it.getLong(0), it.getLong(1), it.getInt(2) == 1) }
        val live = open?.let { (s, p) -> Triple(s, System.currentTimeMillis(), p) }
        return (stored.filter { it.first != live?.first } + listOfNotNull(live)).map { (s, e, p) -> Triple(maxOf(s, from), minOf(e, to), p) }.filter { it.second > it.first }
    }

    fun tests() = rows("SELECT at, exit, via, server, ip, down, up, idle, busy_down, busy_up FROM test ORDER BY at DESC") {
        Test(it.getLong(0), it.getString(1), it.getString(2), it.getString(3), it.getString(4), it.getLong(5), it.getLong(6), it.getInt(7), it.getInt(8), it.getInt(9))
    }

    // ---- live view -----------------------------------------------------------------------------------------------

    /** The last 60 one-second samples of download per exit (bits/s), plus the current totals; only while watched. */
    data class Live(val down: Map<String, FloatArray> = emptyMap(), val downNow: Long = 0, val upNow: Long = 0)

    internal val _live = MutableStateFlow(Live())
    val live = _live.asStateFlow()

    /** Stats is on screen with the app in the foreground: sample every second (peaks, live chart), else every 15 s. */
    @Volatile var watching = false
        set(v) { if (field != v) { field = v; post { Collector.retimeNow() } } }
}

/**
 * The engine's status and connection streams, reconciled per connection into [Stats]. Callbacks copy what they need
 * and queue it to the worker; the fields below are touched on the worker only.
 */
internal object Collector {
    private class Conn(val app: String, val exit: String, val server: String?, var up: Long, var down: Long)
    private class Ev(val type: Long, val id: String, val total: Pair<Long, Long>?, val delta: Pair<Long, Long>, val uid: Int, val tag: String?, val closed: Boolean, val created: Long, val own: Boolean)

    private var client: CommandClient? = null
    private var interval = 0L
    private val seen = HashMap<String, Conn>()
    private val owners = HashMap<Int, String>()
    private var lastFlush = 0L
    private var lastTick = 0L
    private val window = HashMap<String, Long>()
    private val windowUpBy = HashMap<String, Long>()
    private var windowUp = 0L
    /** Drawn on the live chart but never a peak: bytes reported at a close (they span an unknown time), and our own. */
    private val closing = HashMap<String, Long>()
    private var closingUp = 0L
    /** Connections first seen in a replay are counted in full if they began after this (a resubscription gap), else baselined. */
    private var countFrom = Long.MAX_VALUE
    /** When this subscription began, and last heard from the engine (wall ms). */
    private var startedAt = 0L
    private var heard = 0L
    private var retries = 0
    /** Connections counted up to their close: a replay lists recently closed ones again, and they mustn't count twice. */
    private val done = object : LinkedHashSet<String>() {
        override fun add(element: String) = super.add(element).also { if (size > 4096) remove(first()) }
    }
    val running get() = client != null
    private val fast get() = interval == 1_000_000_000L

    fun startNow(retime: Boolean = false) {
        val now = System.currentTimeMillis()
        // A resubscription counts what began since the last one stopped hearing (kept across failed attempts until a
        // replay lands); a fresh start baselines what's open.
        countFrom = if (retime && client != null) minOf(countFrom, (heard.takeIf { it > 0 } ?: now) - 2_000) else Long.MAX_VALUE
        stopClient()
        startedAt = now
        interval = if (Stats.watching) 1_000_000_000L else 15_000_000_000L
        val c = Libbox.newCommandClient(Handler(Stats.gen), CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus); addCommand(Libbox.CommandConnections); statusInterval = interval
        })
        client = c
        val g = Stats.gen
        Thread { runCatching { c.connect() }.onFailure { Log.w("Unbraid", "stats: no engine connection"); retry(g) } }.start()
    }

    /** A subscription the engine dropped (not one we stopped) comes back after a growing pause, the ledger kept. */
    private fun retry(gen: Int) {
        val wait = 2_000L shl retries.coerceAtMost(5)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Stats.post { if (gen == Stats.gen && client != null) { retries++; startNow(retime = true) } }
        }, wait)
    }

    private fun stopClient() {
        client?.let { c -> client = null; Thread { runCatching { c.disconnect() } }.start() }
        Stats.gen++
        lastTick = 0; heard = 0; window.clear(); windowUpBy.clear(); windowUp = 0; closing.clear(); closingUp = 0
        Stats._live.value = Stats.Live()
    }

    /** Recording ends: the ledger goes too (a later start baselines whatever is open then). */
    fun stopNow() { stopClient(); seen.clear(); done.clear(); retries = 0 }

    /** A new sampling rate needs a new subscription. The ledger stays: its replay reconciles, nothing is lost. */
    fun retimeNow() { if (client != null) startNow(retime = true) }

    /** gomobile calls back on its own threads: copy the values out and queue them with this subscription's generation. */
    private class Handler(val gen: Int) : CommandClientHandler {
        override fun writeConnectionEvents(events: ConnectionEvents) {
            val list = ArrayList<Ev>()
            val it = events.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val c = e.connection
                list += Ev(e.type.toLong(), e.id, c?.let { x -> x.uplinkTotal to x.downlinkTotal }, e.uplinkDelta to e.downlinkDelta,
                    c?.processInfo?.userID ?: -1, c?.let { x -> first(x.chain()) }, (c?.closedAt ?: 0) > 0, c?.createdAt ?: 0, c?.inbound == Compiler.PROBE)
            }
            val reset = events.reset
            Stats.post { if (gen == Stats.gen) { heard = System.currentTimeMillis(); retries = 0; apply(list, reset) } }
        }

        override fun writeStatus(message: StatusMessage) = Stats.post {
            if (gen != Stats.gen) return@post
            val now = System.currentTimeMillis()
            heard = now
            // Quiet seconds bring no traffic batch: the status tick moves the live chart on by the seconds missed.
            if (lastTick == 0L) lastTick = android.os.SystemClock.elapsedRealtime()
            val quiet = android.os.SystemClock.elapsedRealtime() - lastTick
            if (fast && quiet > 1_600) sample(now, ((quiet + 500) / 1000).toInt())
            if (now - lastFlush > 25_000) { lastFlush = now; Stats.flushNow(); Stats.checkpointNow() }
        }

        override fun connected() = Unit
        override fun disconnected(message: String?) = retry(gen)
        override fun clearLogs() = Unit
        override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit
        override fun setDefaultLogLevel(level: Int) = Unit
        override fun updateClashMode(newMode: String?) = Unit
        override fun writeGroups(message: OutboundGroupIterator?) = Unit
        override fun writeLogs(messageList: LogIterator?) = Unit
        override fun writeOutbounds(message: OutboundGroupItemIterator?) = Unit
    }

    /**
     * New and Closed carry a connection's totals, Update the change since the last one. A connection's app, exit and
     * server are fixed when it is first seen, against the configuration the engine actually runs.
     */
    private fun apply(events: List<Ev>, reset: Boolean) {
        val now = System.currentTimeMillis()
        val setup = UnbraidVpn.running ?: Unbraid.store.setup.value
        val servers = UnbraidVpn.state.value.servers
        val tags = UnbraidVpn.tags
        val open = { e: Ev ->
            val app = if (e.own) Stats.ctx.packageName else appOf(e.uid)
            val exit = (if (e.own) SpeedTest.exitOn(e.tag) else null) ?: exitOf(e.tag, app, setup, tags)
            Conn(app, exit, servers[exit]?.name, 0, 0)
        }
        val replayed = HashSet<String>()
        var batch = false
        for (e in events) {
            var up: Long
            var down: Long
            val c: Conn
            when (e.type) {
                Libbox.ConnectionEventNew -> {
                    val t = e.total ?: continue
                    replayed += e.id
                    val known = seen[e.id]
                    if (known != null) {
                        // Replayed and already counted: add only what it did while we weren't listening.
                        up = (t.first - known.up).coerceAtLeast(0); down = (t.second - known.down).coerceAtLeast(0); c = known
                        if (e.closed) { seen.remove(e.id); done += e.id } else { known.up = t.first; known.down = t.second }
                    } else {
                        if (e.id in done) continue
                        // Unknown in a replay: born during a resubscription gap → count it; open since before recording → baseline.
                        val fresh = !reset || e.created >= countFrom
                        if (e.closed && !fresh) continue
                        c = open(e).also { it.up = t.first; it.down = t.second }
                        if (e.closed) done += e.id else seen[e.id] = c
                        if (!fresh) continue
                        up = t.first; down = t.second
                    }
                }
                Libbox.ConnectionEventUpdate -> {
                    batch = true
                    c = seen[e.id] ?: continue
                    up = e.delta.first; down = e.delta.second
                    c.up += up; c.down += down
                }
                Libbox.ConnectionEventClosed -> {
                    val t = e.total ?: continue
                    // The engine's event queue can drop a New: a connection born while listening still counts in full.
                    c = seen.remove(e.id) ?: if (e.id !in done && e.created >= startedAt) open(e) else continue
                    done += e.id
                    up = (t.first - c.up).coerceAtLeast(0); down = (t.second - c.down).coerceAtLeast(0)
                }
                else -> continue
            }
            Stats.add(now, c.app, c.exit, c.server, up, down)
            // The live window takes only what flowed just now: never a replay's catch-up or a New's backlog.
            // unbraid's own traffic (speed tests, health checks) is drawn but never a "seen" peak: those are your apps'.
            val own = c.app == Stats.ctx.packageName
            if (fast && !reset && c.exit != DIRECT) when {
                own -> { closing.merge(c.exit, down, Long::plus); closingUp += up }
                e.type == Libbox.ConnectionEventUpdate -> { window.merge(c.exit, down, Long::plus); windowUpBy.merge(c.exit, up, Long::plus); windowUp += up }
                e.type == Libbox.ConnectionEventClosed -> { closing.merge(c.exit, down, Long::plus); closingUp += up }
            }
        }
        // After a reload the old engine's connections never close: a full replay says which ones still exist.
        if (reset) { seen.keys.retainAll(replayed); countFrom = Long.MAX_VALUE }
        if (fast && batch) sample(now, 1)
    }

    /**
     * Closes the live window: the series, headline and peaks all come from it. The engine sends one traffic batch per
     * second with that second's bytes, so a batch is one sample whenever it arrives (timing it here would turn a late
     * batch and the one after into a spike and a gap). Quiet seconds send none: status ticks fill them in.
     */
    private fun sample(now: Long, seconds: Int) {
        lastTick = android.os.SystemClock.elapsedRealtime()
        val prev = Stats.live.value.down
        val down = (prev.keys + window.keys + closing.keys).associateWith { k ->
            val bps = (window[k] ?: 0L) * 8 / seconds
            val upBps = (windowUpBy[k] ?: 0L) * 8 / seconds
            if (bps > 0 || upBps > 0) Stats.peak(now, k, bps, upBps)
            val shown = bps + (closing[k] ?: 0L) * 8 / seconds
            val series = prev[k] ?: FloatArray(60)
            series.copyOfRange(seconds.coerceAtMost(60), 60) + FloatArray(seconds.coerceAtMost(60)) { shown.toFloat() }
        }
        Stats._live.value = Stats.Live(down, down.values.sumOf { it.last().toDouble() }.toLong(), (windowUp + closingUp) * 8 / seconds)
        window.clear(); windowUpBy.clear(); windowUp = 0; closing.clear(); closingUp = 0
    }

    private fun appOf(uid: Int) = if (uid < 0) "?" else owners.getOrPut(uid) { Stats.ctx.packageManager.getPackagesForUid(uid)?.minOrNull() ?: "uid:$uid" }

    /** The tunnel tag says which tunnel; when routes share it, the app's own route among them says which route. */
    private fun exitOf(tag: String?, app: String, setup: app.unbraid.data.Setup, tags: Map<String, String>): String {
        if (tag == null || tag == "direct") return DIRECT
        if (tag == "home-wg") return Stats.HOME
        val candidates = tags.filterValues { it == tag }.keys
        if (candidates.isEmpty()) return if (tag == Compiler.TS_TAG) Stats.HOME else tag.removePrefix("r-")
        val own = setup.routeOf(app) // the running setup's assignment, not one still being edited
        return if (own in candidates) own else if (tag == Compiler.TS_TAG) Stats.HOME else candidates.first()
    }

    private fun first(s: StringIterator?): String? {
        if (s == null) return null
        while (s.hasNext()) s.next().let { if (it.startsWith("r-") || it.startsWith("home-") || it == "direct") return it }
        return null
    }
}
