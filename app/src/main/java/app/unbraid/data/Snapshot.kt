package app.unbraid.data

import java.io.File

/**
 * The last good copy of a provider's own server list, in app storage. Its WireGuard keys came from the provider over
 * HTTPS, so it stands in when that list can't be read; gluetun's catalogue never supplies a key for these providers.
 */
class Snapshot(private val load: () -> String?, private val save: (String) -> Unit) {
    /** The copy last written in this process: still used if saving it to storage failed. */
    @Volatile private var held: String? = null

    fun read(): String? = held ?: runCatching(load).getOrNull()
    fun write(body: String) { held = body; runCatching { save(body) }.onFailure { runCatching { android.util.Log.w("Unbraid", "snapshot: not saved") } } }

    companion object {
        /** App storage, set once at start ([app.unbraid.Unbraid]); null in unit tests, where nothing is kept. */
        @Volatile var dir: File? = null

        private val lock = Any()

        /** Writes go to a synced temporary file that replaces the old copy in one rename, so a reader never sees half a list. */
        fun file(name: String) = Snapshot(
            load = { synchronized(lock) { dir?.let { File(it, name) }?.takeIf(File::exists)?.readText() } },
            save = { body ->
                synchronized(lock) {
                    val d = dir ?: return@synchronized
                    val tmp = File(d, "$name.tmp")
                    java.io.FileOutputStream(tmp).use { out -> out.write(body.toByteArray()); out.fd.sync() }
                    if (!tmp.renameTo(File(d, name))) { tmp.delete(); throw java.io.IOException("snapshot $name not replaced") }
                }
            },
        )

        fun memory(initial: String? = null): Snapshot { var v = initial; return Snapshot({ v }, { v = it }) }
    }
}
