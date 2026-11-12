package app.unbraid

import app.unbraid.ui.checkAtOnce
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOptInTest {
    /** Saying yes on the welcome page, before the terms are accepted, contacts nobody; turning it on later checks at once. */
    @Test fun noCheckBeforeTheTermsAreAccepted() {
        assertFalse(checkAtOnce(available = true, was = false, now = true, termsOk = false))
        assertTrue(checkAtOnce(available = true, was = false, now = true, termsOk = true))
        assertFalse(checkAtOnce(available = true, was = true, now = true, termsOk = true)) // already on
        assertFalse(checkAtOnce(available = true, was = false, now = false, termsOk = true)) // turned off
        assertFalse(checkAtOnce(available = false, was = false, now = true, termsOk = true)) // a build without checks
    }
}
