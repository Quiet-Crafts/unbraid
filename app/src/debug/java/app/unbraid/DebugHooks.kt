package app.unbraid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.unbraid.data.Nord
import app.unbraid.data.NordApi
import app.unbraid.data.Setup
import app.unbraid.vpn.UnbraidVpn
import kotlin.concurrent.thread
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * adb shell am broadcast -a app.unbraid.DEBUG -n app.unbraid/.DebugHooks --es cmd <signin|setup|start|stop|state|dump|all|allstate|go> [--es arg ...]
 */
class DebugHooks : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val r = goAsync()
        thread {
            val s = Unbraid.store
            val out = runCatching {
                when (i.getStringExtra("cmd")) {
                    "signin" -> { s.account("nord", Nord.signIn(mapOf("token" to i.getStringExtra("arg")!!))); "signed in" }
                    "setup" -> { s.update { NordApi.json.decodeFromString(Setup.serializer(), i.getStringExtra("arg")!!) }; "setup ok" }
                    "start" -> { UnbraidVpn.start(ctx); "starting" }
                    "stop" -> { UnbraidVpn.stop(ctx); "stopping" }
                    "pause" -> { UnbraidVpn.pause(ctx, i.getStringExtra("arg")!!.toInt()); "pausing" }
                    "signout" -> { s.account(i.getStringExtra("arg")!!, null); "signed out" }
                    "account" -> {
                        val o = NordApi.json.parseToJsonElement(i.getStringExtra("arg")!!).jsonObject
                        val p = app.unbraid.data.Providers.get(o["provider"]!!.jsonPrimitive.content)!!
                        s.account(p.id, p.signIn(o["input"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }))
                        "account ${p.id} ok, ${p.countries(s.accounts.value.getValue(p.id)).size} countries"
                    }
                    "health" -> kotlinx.coroutines.runBlocking { val out = mutableListOf<String>(); for (t in UnbraidVpn.tags.values.distinct().filter { it.startsWith("r-") }) out += "$t=${app.unbraid.vpn.Health.reachable(t)}"; out.joinToString() }
                    "ndt" -> { val t = i.getStringExtra("arg")!!; val sv = app.unbraid.stats.Ndt7.locate(t, null)!!; "ndt $t ${sv.city} ping=${app.unbraid.stats.Ndt7.ping(t, sv)}ms down=${app.unbraid.stats.Ndt7.download(t, sv.download, { false }) {} / 1_000_000}Mbps" }
                    "dl" -> { val (t, host, path) = i.getStringExtra("arg")!!.split('|'); val c = app.unbraid.vpn.Probe.open(t, host)
                        c.output.write("GET $path HTTP/1.0\r\nHost: $host\r\n\r\n".toByteArray()); app.unbraid.vpn.Probe.head(c.input)
                        val buf = ByteArray(1 shl 16); val t0 = System.nanoTime(); var n = 0L
                        while (System.nanoTime() - t0 < 10_000_000_000L) { val r = c.input.read(buf); if (r < 0) break; n += r }
                        c.close(); "dl $t ${n * 8_000 / ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1) / 1_000_000}Mbps" }
                    "test" -> { app.unbraid.stats.SpeedTest.start(i.getStringExtra("arg")!!); "test started" }
                    "run" -> app.unbraid.stats.SpeedTest.run.value.toString()
                    "dump" -> NordApi.json.encodeToString(Setup.serializer(), s.setup.value) + " accounts=" + s.accounts.value.keys + " tags=" + UnbraidVpn.tags
                    "all" -> { app.unbraid.stats.SpeedTest.startAll(i.getStringExtra("arg")!!.split(','), UnbraidVpn.metered); "all started" }
                    "allstate" -> app.unbraid.stats.SpeedTest.all.value?.let { a -> "${a.phase} down=${a.down} up=${a.up} window=${a.windowMs} metered=${a.metered} now=${a.now} error=${a.error} | " +
                        a.legs.joinToString(" | ") { "${it.exit} ${it.server?.city}${if (it.missing) "(nearest)" else ""} limited=${it.limited} ping=${it.ping?.median} down=${it.down} up=${it.up} now=${it.now} err=${it.error}" } }.toString()
                    "go" -> { app.unbraid.stats.SpeedTest.go(i.getStringExtra("arg").orEmpty().split(',').filter(String::isNotEmpty).toSet()); "go" }
                    "live" -> app.unbraid.stats.Stats.live.value.down.entries.joinToString(" | ") { (k, v) -> "$k " + v.joinToString(",") { (it / 1e6).toInt().toString() } }
                    "keys" -> app.unbraid.data.WgKeys.generate().let { "keys ok sdk=${android.os.Build.VERSION.SDK_INT} priv=${it.private.length} pub=${it.public.length}" }
                    else -> UnbraidVpn.state.value.toString()
                }
            }.getOrElse { "error ${it.message}" }
            Log.i("UNBRAID_DEBUG", out)
            r.finish()
        }
    }
}
