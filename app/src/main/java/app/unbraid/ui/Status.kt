package app.unbraid.ui

/** How a provider stands, in Settings: a colour, always with words. */
enum class Tone { Good, Warn, Bad, Idle }

/**
 * A provider's status from what the app already knows (no extra calls): [refused] its account, its key still [settingUp],
 * its routes [blocked] for a passing reason, a route [dropping] (its warning), its only list published long ago ([oldList],
 * that date), its saved server list over a day old
 * (![listFresh]), its routes [connected].
 */
fun providerStatus(name: String, used: Boolean, refused: Boolean, settingUp: Boolean, blocked: Boolean, listFresh: Boolean, connected: Boolean, dropping: String? = null, oldList: String? = null): Pair<Tone, String> = when {
    refused -> Tone.Bad to "$name refused this phone. Sign in again"
    !used -> Tone.Idle to "Not used"
    settingUp -> Tone.Warn to "Setting up your key"
    blocked -> Tone.Warn to "Can't reach its servers right now"
    dropping != null -> Tone.Warn to dropping
    oldList != null -> Tone.Warn to "Its server list is from $oldList and may be out of date"
    !listFresh -> Tone.Warn to "Using an older server list"
    connected -> Tone.Good to "Connected"
    else -> Tone.Good to "Server list up to date"
}

/** A catalogue's publication date ([app.unbraid.data.Catalog.published], epoch seconds), e.g. "6 Aug 2026". */
fun publishedOn(epochSeconds: Long): String = java.time.Instant.ofEpochSecond(epochSeconds).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH))

/** Older than this, a provider's only server list turns its status amber. */
const val OLD_LIST_MS = 60L * 86_400_000
