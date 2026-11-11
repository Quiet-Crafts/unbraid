package app.unbraid.support

import kotlinx.serialization.Serializable

@Serializable
data class NudgeState(
    /** When the app was installed, in epoch milliseconds. */
    val installedAtMs: Long = 0,
    /** Epoch days on which the app connected. */
    val connectedDays: List<Long> = emptyList(),
    /** Times an ask was shown, in epoch milliseconds. */
    val shownAtMs: List<Long> = emptyList(),
    /** When a snoozed ask may appear again, in epoch milliseconds. */
    val snoozeUntilMs: Long = 0,
    /** Whether support asks are disabled. */
    val disabled: Boolean = false,
)

/** When the support card may show: rare, never on a first week, and never again once turned off. All of it is a pure function of [NudgeState]. */
object Nudges {
    const val DAY = 86_400_000L
    private const val MIN_DAYS = 3
    private const val KEPT = 4

    /** Notes that the app was connected on [epochDay]; three distinct days are all the rule needs. */
    fun connectedDay(s: NudgeState, epochDay: Long) = if (epochDay in s.connectedDays || s.connectedDays.size >= MIN_DAYS) s else s.copy(connectedDays = s.connectedDays + epochDay)

    /** Whether to ask now; [calm] says the screen is quiet enough (nothing else is asking, nothing is going wrong). */
    fun due(s: NudgeState, now: Long, calm: Boolean) = !s.disabled && calm && now >= s.snoozeUntilMs && now - s.installedAtMs >= 7 * DAY &&
        s.connectedDays.size >= MIN_DAYS && s.shownAtMs.none { now - it < 90 * DAY } && s.shownAtMs.count { now - it < 365 * DAY } < KEPT

    /** Records that the card showed, and rests it for 90 days (also what closing it means). */
    fun shown(s: NudgeState, now: Long) = s.copy(shownAtMs = (s.shownAtMs + now).takeLast(KEPT), snoozeUntilMs = now + 90 * DAY)

    fun disable(s: NudgeState) = s.copy(disabled = true)
}
