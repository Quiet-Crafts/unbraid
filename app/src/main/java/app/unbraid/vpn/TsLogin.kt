package app.unbraid.vpn

import java.net.URI

/**
 * Tailscale sign-in links come from the control server, so a malicious or compromised one could send any URI: an
 * `intent:` link into another app, or a phishing page. Only an HTTPS link to the control server itself (or to
 * Tailscale's own servers when none is set) opens straight away; another web address is shown first; anything else
 * never opens.
 */
object TsLogin {
    enum class Verdict { OPEN, ASK, REFUSE }

    /** [url] is the cleaned link to launch (lowercase scheme, no surrounding space); null when refused. */
    class Result(val verdict: Verdict, val url: String?, val host: String?)

    private val TAILSCALE_CONTROL = setOf("controlplane.tailscale.com", "login.tailscale.com")

    /**
     * Whether a sign-in waits, from a Tailscale status: yes with a sign-in link, no once signed in (Running), else as
     * [was]. A restarted engine reports no link at first: that alone never ends a sign-in.
     */
    fun pending(authUrl: String, backend: String?, was: Boolean) = when { authUrl.isNotEmpty() -> true; backend == "Running" -> false; else -> was }

    fun check(url: String, controlUrl: String?): Result {
        val refuse = Result(Verdict.REFUSE, null, null)
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return refuse
        val host = u.host?.lowercase()?.trimEnd('.')
        if (!u.scheme.equals("https", ignoreCase = true) || host.isNullOrEmpty() || u.rawUserInfo != null) return refuse
        if (u.port != -1 && u.port !in 1..65535) return refuse
        val clean = "https" + url.trim().substring(u.scheme.length)
        // No control server set, or Tailscale's own: Tailscale's hosts are trusted. A custom server (even one that
        // can't be parsed) trusts only its own host, so a typo never widens trust to Tailscale.
        val control = controlUrl?.trim()?.takeIf { it.isNotEmpty() }?.let { c ->
            runCatching { URI(c).host }.getOrNull()?.lowercase()?.trimEnd('.') ?: ""
        }
        val trusted = if (control == null || control in TAILSCALE_CONTROL) tailscale(host) else host == control
        return Result(if (trusted) Verdict.OPEN else Verdict.ASK, clean, host)
    }

    private fun tailscale(host: String) = host == "tailscale.com" || host.endsWith(".tailscale.com")
}
