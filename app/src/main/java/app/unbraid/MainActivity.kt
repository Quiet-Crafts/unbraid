package app.unbraid

import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.SeekableTransitionState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import app.unbraid.ui.sharedRoute
import app.unbraid.ui.LocalShared
import app.unbraid.ui.LocalScreen
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.SharedTransitionLayout
import androidx.activity.compose.PredictiveBackHandler
import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.ui.AppsScreen
import app.unbraid.ui.Caption
import app.unbraid.ui.tap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import app.unbraid.ui.Ink
import app.unbraid.ui.LOCAL_NETWORK
import app.unbraid.ui.NavBar
import app.unbraid.ui.PickerScreen
import app.unbraid.ui.RoutesScreen
import app.unbraid.ui.SettingsScreen
import app.unbraid.ui.StatsScreen
import app.unbraid.ui.SignInScreen
import app.unbraid.ui.UnbraidTheme
import app.unbraid.ui.Tab
import app.unbraid.ui.Type
import app.unbraid.ui.Ui
import app.unbraid.ui.needsLocalNetwork
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.semantics.clearAndSetSemantics
import app.unbraid.ui.AppsDisclosure
import app.unbraid.ui.LeakSheet
import app.unbraid.ui.Onboarding
import app.unbraid.ui.TourStop
import app.unbraid.ui.TourOverlay
import app.unbraid.ui.VpnDisclosure
import app.unbraid.ui.Welcome
import app.unbraid.ui.settingsHub
import app.unbraid.vpn.UnbraidVpn
import app.unbraid.vpn.VpnState

class MainActivity : ComponentActivity() {
    private val ui: Ui by viewModels()

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // Still unprepared: refused, or another VPN app holds Always-on (Routes then says so).
        if (VpnService.prepare(this) == null) ui.connect() else Onboarding.consentFailed = true
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /**
     * The sheet asking first: "vpn" (the VPN disclosure, before the first connect), "apps" (the installed-apps
     * disclosure, before the list is read) or "leak" (the leak-proof setup, after the first connect).
     */
    private var sheet by mutableStateOf<String?>(null)
    private var connectAfter = false

    /** Connect. Until the VPN disclosure is accepted it comes first, before Android's own VPN consent. */
    fun connect() {
        if (!Onboarding.vpnOk) { connectAfter = true; sheet = "vpn"; return }
        proceed()
    }

    private fun proceed() {
        // Other apps' LAN bypasses the tunnel at OS level; Unbraid's own Tailscale and home tunnel need the permission.
        val ask = listOfNotNull(
            Manifest.permission.POST_NOTIFICATIONS.takeIf { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED },
            // Under "Block connections without VPN", LAN traffic runs through Unbraid's own sockets too.
            LOCAL_NETWORK.takeIf { needsLocalNetwork(ui.setup.value, app.unbraid.vpn.UnbraidVpn.state.value.lockdown && ui.setup.value.prefs.lan) },
        )
        if (ask.isNotEmpty()) permissions.launch(ask.toTypedArray())
        VpnService.prepare(this)?.let { vpnConsent.launch(it) } ?: ui.connect()
    }

    // Android's Always-on and Block connections, and the installed apps, can change while unbraid is in the background.
    override fun onResume() { super.onResume(); UnbraidVpn.refreshFlags(this); ui.refreshApps(); app.unbraid.stats.Stats.maintain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.setHideOverlayWindows(true) // no tapjacking over sign-in or connect
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        Onboarding.init(this)
        setContent {
            UnbraidTheme {
              if (!Onboarding.termsOk) {
                // A new install gets the whole welcome; someone updating from before the terms only the terms.
                val fresh = remember { !ui.signedIn.value && !ui.disclosed.value }
                Welcome(fresh) { Onboarding.acceptTerms(fresh) }
              } else {
                val signedIn by ui.signedIn.collectAsStateWithLifecycle()
                var tab by rememberSaveable { mutableStateOf(Tab.Routes) }
                // Picker target: null = closed, "" = new route, otherwise a route id. Prefixed "app:" = new route for that app.
                var picker by rememberSaveable { mutableStateOf<String?>(null) }

                var adding by rememberSaveable { mutableStateOf(false) }
                val vpn by ui.vpn.collectAsStateWithLifecycle()
                val disclosed by ui.disclosed.collectAsStateWithLifecycle()

                // The tour: a stop index, -1 = off. A new install gets it once it is set up; Settings replays it.
                var tour by rememberSaveable { mutableStateOf(-1) }
                LaunchedEffect(signedIn) { if (signedIn && Onboarding.tourPending && tour < 0) tour = 0 }
                LaunchedEffect(tour) {
                    if (tour < 0) return@LaunchedEffect
                    picker = null; adding = false
                    TourStop.entries[tour].tab.let { if (it == Tab.Settings && tab != Tab.Settings) settingsHub(); tab = it }
                }
                val endTour = { tour = -1; Onboarding.tourSeen(); tab = Tab.Routes }

                // Leak-proof setup: offered once after the first connect, unless Android's
                // Always-on and Block are already on; never on top of the tour.
                LaunchedEffect(vpn.phase, vpn.alwaysOn, vpn.lockdown, tour, sheet) {
                    if (vpn.phase != VpnState.Phase.Off) Onboarding.consentFailed = false
                    if (vpn.phase != VpnState.Phase.On) return@LaunchedEffect
                    // Protection is checked live each time, so turning blocking off later brings the offer or reminder back.
                    if (!(vpn.alwaysOn && vpn.lockdown) && Onboarding.leak == 0 && tour < 0 && sheet == null) sheet = "leak"
                }

                // The screen shown; the picker's carries its route, so a closing picker keeps its identity while it leaves.
                val screenNow = when {
                    !signedIn -> "signin"
                    adding -> "add"
                    picker != null -> "picker:$picker"
                    else -> tab.name
                }
                // Seekable, so the predictive back gesture can drive the real transition (the picker shrinking back into
                // its route) and commit or cancel it.
                val screens = remember { SeekableTransitionState(screenNow) }
                LaunchedEffect(screenNow) { screens.animateTo(screenNow) }
                val scope = rememberCoroutineScope()
                val motion = MaterialTheme.motionScheme
                PredictiveBackHandler(adding || picker != null) { events ->
                    try {
                        events.collect { screens.seekTo(it.progress, targetState = tab.name) }
                        if (adding) adding = false else picker = null
                    } catch (e: CancellationException) {
                        scope.launch { screens.animateTo(screens.currentState) }
                        throw e
                    }
                }
                BackHandler(picker == null && !adding && tab != Tab.Routes) { tab = Tab.Routes }

                Box(Modifier.fillMaxSize()) {
                // While touring the app is looked at, not used: TalkBack reads the tour's note only.
                Column(Modifier.fillMaxSize().background(Ink.ground).then(if (tour >= 0) Modifier.clearAndSetSemantics {} else Modifier)) {
                    SharedTransitionLayout(Modifier.weight(1f)) {
                        CompositionLocalProvider(LocalShared provides this) {
                            rememberTransition(screens, label = "screen").AnimatedContent(
                                transitionSpec = { fadeIn(motion.fastEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                            ) { screen ->
                                CompositionLocalProvider(LocalScreen provides this) {
                                    when {
                                        screen == "signin" -> SignInScreen(ui)
                                        screen == "add" -> SignInScreen(ui) { adding = false }
                                        // The route that was tapped grows into its picker (container transform).
                                        screen.startsWith("picker:") -> screen.removePrefix("picker:").let { target ->
                                            Box(Modifier.fillMaxSize().then(sharedRoute(target, RoundedCornerShape(28.dp)))) {
                                                PickerScreen(
                                                    ui, target, close = { picker = null },
                                                    settings = { picker = null; tab = Tab.Settings }, addProvider = { picker = null; adding = true },
                                                )
                                            }
                                        }
                                        screen == Tab.Routes.name -> RoutesScreen(ui, ::connect, leakSetup = { sheet = "leak" }) { picker = it }
                                        screen == Tab.Apps.name -> AppsScreen(ui, disclose = { sheet = "apps" }) { picker = it }
                                        screen == Tab.Stats.name -> StatsScreen(ui)
                                        else -> SettingsScreen(ui, addProvider = { adding = true }, tour = { tour = 0 })
                                    }
                                }
                            }
                        }
                    }
                    // A change that applied but couldn't be written, wherever it was made: it would be gone after a restart.
                    val unsaved by Unbraid.store.unsaved.collectAsStateWithLifecycle()
                    if (unsaved) Caption("Some of your changes couldn't be saved and would be lost when unbraid restarts. Tap to try saving again.",
                        Modifier.fillMaxWidth().tap({ kotlin.concurrent.thread { Unbraid.store.saveAgain() } }).padding(horizontal = 24.dp, vertical = 12.dp), color = Ink.bad)
                    if (signedIn && picker == null && !adding) NavBar(tab) { tab = it }
                }
                if (tour >= 0) TourOverlay(
                    tour, disclosed, back = { tour-- }, close = endTour,
                    next = { if (tour == TourStop.entries.lastIndex) endTour() else tour++ },
                )
                }
                when (sheet) {
                    "vpn" -> VpnDisclosure(
                        agree = { Onboarding.acceptVpn(); sheet = null; if (connectAfter) { connectAfter = false; proceed() } },
                        dismiss = { sheet = null; connectAfter = false },
                    )
                    "apps" -> AppsDisclosure(agree = { ui.acceptDisclosure(); sheet = null }, dismiss = { sheet = null })
                    "leak" -> {
                        val both = vpn.alwaysOn && vpn.lockdown
                        LeakSheet(
                            vpn.alwaysOn, vpn.lockdown,
                            open = { runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
                            later = { if (!both) Onboarding.snoozeLeak(); sheet = null },
                            never = { Onboarding.leak(3); sheet = null },
                            done = { sheet = null },
                        )
                    }
                }
              }
            }
        }
    }
}
