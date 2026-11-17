package app.unbraid

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Websites saved and websites in use are told apart in one place, [app.unbraid.data.Sites.applied]: a rule that doesn't
 * apply opens no tunnel, protects nothing and is in no count of a route or of traffic. Every place that reads the saved
 * map needs the SavedSites opt-in; until every file that reads it says so and the opt-in is an error, each place is also listed here with why it may; a new one fails until it is checked and listed, or goes through `applied`.
 */
class SitesInUseTest {
    /** File to the number of times its code reads a saved website map (`.sites`), and why each may. */
    private val allowed = mapOf(
        "data/Sites.kt" to 2, // applied itself
        "data/Model.kt" to 8, // profile plumbing: switching, saving, mending, comparing, editing
        "data/Backup.kt" to 3, // what a backup holds and its checks
        "data/RestoreTraffic.kt" to 4, // what a restore changes, saved against saved
        "ui/AppsScreen.kt" to 2, // the Websites list's size and its 50 limit
        "ui/Backup.kt" to 2, // what a backup holds
        "ui/Profiles.kt" to 5, // a profile's saved websites, carried and described
        "ui/RoutesScreen.kt" to 3, // what removing a route takes away, saved either way
        "ui/Ui.kt" to 4, // editing
        "ui/Websites.kt" to 7, // the list, its rows and the Add sheet: every saved website
        "vpn/Compiler.kt" to 2, // routes that websites name but no longer exist, and names a lookup rule answers first: refused either way
        "vpn/LinkPicker.kt" to 2, // a change to the saved websites restarts the engine
        "vpn/Widgets.kt" to 2, // a widget row's own count field
    )

    @Test fun everyReadOfSavedWebsitesIsListed() {
        val root = File("src/main/java/app/unbraid")
        val found = root.walk().filter { it.extension == "kt" }.associate { f ->
            // Code only: KDoc and line comments may name the field.
            val code = f.readLines().filter { l -> l.trim().let { !it.startsWith("*") && !it.startsWith("//") && !it.startsWith("/**") } }.joinToString("\n") { it.substringBefore(" // ") }
            f.relativeTo(root).path to Regex("""\.sites\b""").findAll(code).count()
        }.filterValues { it > 0 }
        assertEquals(allowed.toSortedMap(), found.toSortedMap())
    }
}
