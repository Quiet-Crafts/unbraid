package app.unbraid

import app.unbraid.ui.Fetch
import app.unbraid.ui.startFetch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogFetchTest {
    /** A fetch shows as loading; a Retry while it runs starts no second one; after a failure a Retry starts again. */
    @Test fun oneFetchAtATimeAndRetryAfterFailure() {
        val loading = startFetch(emptyMap(), "nord")!!
        assertEquals(Fetch.Loading, loading["nord"])
        assertNull(startFetch(loading, "nord"))
        assertEquals(Fetch.Loading, startFetch(loading, "mullvad")!!["mullvad"]) // another provider fetches alongside
        val failed = loading + ("nord" to Fetch.Failed("No connection"))
        assertEquals(Fetch.Loading, startFetch(failed, "nord")!!["nord"])
    }
}
