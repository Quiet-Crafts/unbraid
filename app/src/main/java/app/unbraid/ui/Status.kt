package app.unbraid.ui

/** How a provider stands, in Settings: a colour, always with words. */
enum class Tone { Good, Warn, Bad, Idle }

/**
 * A provider's status from what the app already knows (no extra calls): [refused] its account, its key still [settingUp],
 * its routes [blocked] for a passing reason, its saved server list over a day old (![listFresh]), its routes [connected].
 */
fun providerStatus(name: String, used: Boolean, refused: Boolean, settingUp: Boolean, blocked: Boolean, listFresh: Boolean, connected: Boolean): Pair<Tone, String> = when {
    refused -> Tone.Bad to "$name refused this phone. Sign in again"
    !used -> Tone.Idle to "Not used"
    settingUp -> Tone.Warn to "Setting up your key"
    blocked -> Tone.Warn to "Can't reach its servers right now"
    !listFresh -> Tone.Warn to "Using an older server list"
    connected -> Tone.Good to "Connected"
    else -> Tone.Good to "Server list up to date"
}
