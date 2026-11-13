package app.unbraid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screen model starts work on other threads from its constructor (catalogue refreshes, pending releases). Kotlin
 * sets properties in the order they are written, so that work must start after every property: a worker reaching one
 * not set yet would find it null and crash the app.
 */
class UiOrderTest {
    @Test fun backgroundWorkStartsAfterEveryProperty() {
        val lines = File("src/main/java/app/unbraid/ui/Ui.kt").readLines()
        val start = lines.indexOfLast { it.trim() == "releasePending()" }.also { assertTrue(it > 0) }
        val initAt = (start downTo 0).first { lines[it] == "    init {" }
        assertTrue(lines.slice(initAt..start).any { "refreshCountries" in it })
        // Members of the class are indented by four spaces: no property may follow that block.
        val later = lines.drop(start).filter { Regex("""^ {4}(private |internal |@Volatile )*(val|var) """).containsMatchIn(it) }
        assertEquals(emptyList<String>(), later)
    }
}
