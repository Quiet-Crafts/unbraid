package app.unbraid

import app.unbraid.data.AirVpn
import app.unbraid.data.Ivpn
import app.unbraid.data.KeyProvider
import app.unbraid.data.Mullvad
import app.unbraid.data.NordApi
import app.unbraid.data.Nord
import app.unbraid.ui.staleNotice
import app.unbraid.ui.staleRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sign-out whose release couldn't be kept for later is followed, before its release runs, by a new sign-in at the same
 * provider. The release found then runs only when the provider shows the old device isn't the one now in use.
 */
class StaleReleaseTest {
    private fun air(id: String?, own: Boolean = true) = NordApi.json.encodeToString(KeyProvider.Account.serializer(),
        KeyProvider.Account("k", listOf("10.0.0.2/32"), listOf("10.0.0.1"), 1637, 1320, apiKey = "a".repeat(32), device = "unbraid", deviceKey = "dk", deviceId = id, ownDevice = own))
    private fun mullvad(id: String) = NordApi.json.encodeToString(Mullvad.Account.serializer(), Mullvad.Account("1234", "k", "10.64.0.2", id))
    private fun ivpn(token: String) = NordApi.json.encodeToString(Ivpn.Account.serializer(), Ivpn.Account("i-1", token, "k", "172.16.0.2"))

    @Test fun anOldDeviceIsReleasedOnlyWhenItIsAnotherOne() {
        // AirVPN: the old sign-out's own device, a new device now in use: released.
        assertTrue(staleRelease(AirVpn, air("d-old"), air("d-new")))
        // The same device (signed in again with it), a device of the user's that unbraid never removes, or a pasted
        // config that doesn't name its device: dropped.
        assertFalse(staleRelease(AirVpn, air("d-old"), air("d-old", own = false)))
        assertFalse(staleRelease(AirVpn, air("d-old", own = false), air("d-new")))
        assertFalse(staleRelease(AirVpn, air("d-old"), air(null, own = false)))
        assertTrue(staleRelease(Mullvad, mullvad("m-old"), mullvad("m-new")))
        assertFalse(staleRelease(Mullvad, mullvad("m-old"), mullvad("m-old")))
        assertTrue(staleRelease(Ivpn, ivpn("s-old"), ivpn("s-new")))
        assertFalse(staleRelease(Ivpn, ivpn("s-old"), ivpn("s-old")))
        // A provider that can't tell its devices apart, a blob that won't read, or no account now: dropped.
        assertFalse(staleRelease(Nord, "old", "new"))
        assertFalse(staleRelease(AirVpn, "not json", air("d-new")))
        assertFalse(staleRelease(AirVpn, air("d-old"), null))
        assertEquals("An old device of this phone may still be listed at AirVPN. You can remove it on the AirVPN website.", staleNotice("AirVPN"))
    }
}
