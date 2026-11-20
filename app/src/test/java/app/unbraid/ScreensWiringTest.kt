package app.unbraid

import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring of screens that unit tests can't run: checked in the source. */
class ScreensWiringTest {
    private fun src(p: String) = java.io.File("src/main/java/app/unbraid/$p").readText()

    /** The picker's note about servers opens Providers & servers, not the Settings hub. */
    @Test fun pickerOpensProvidersAndServers() {
        assertTrue(src("ui/PickerScreen.kt").contains("Secondary(\"Open Settings\", servers)"))
        assertTrue(src("MainActivity.kt").contains("servers = { pickerAfter = picker; picker = null; settingsProviders(); tab = Tab.Settings }"))
        assertTrue(src("ui/SettingsScreen.kt").contains("fun settingsProviders() { path = listOf(\"providers\") }"))
    }

    /** The Apps tab keeps its search and filters across tabs. */
    @Test fun appsTabKeepsItsState() {
        assertTrue(src("MainActivity.kt").contains("appsState.SaveableStateProvider(Tab.Apps.name) { AppsScreen("))
    }

    /** Tailscale's exit address isn't taken while a speed test has it leave by another node, nor counted as a try. */
    @Test fun exitCheckWaitsForATailscaleTest() {
        val ui = src("ui/Ui.kt")
        assertTrue(ui.contains("val switched = { app.unbraid.stats.SpeedTest.testing || store.tsTestFrom != null }"))
        assertTrue(ui.contains(".filterValues { !held || it != app.unbraid.vpn.Compiler.TS_TAG }"))
        assertTrue(ui.contains("tags.filterValues { !late || it != app.unbraid.vpn.Compiler.TS_TAG }"))
        assertTrue(ui.contains("if (!held) tried.let"))
        assertTrue(src("ui/RoutesScreen.kt").contains("LaunchedEffect(on, vpn.engine, activeIds, testing)"))
    }

    /** The background announcement waits for the terms and for notifications that can be seen. */
    @Test fun updateNotificationIsGated() {
        val v = src("vpn/UnbraidVpn.kt")
        assertTrue(v.contains("if (!app.unbraid.ui.Onboarding.termsOk) return"))
        assertTrue(v.contains("if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(\"updates\")?.importance == NotificationManager.IMPORTANCE_NONE) return"))
    }
}
