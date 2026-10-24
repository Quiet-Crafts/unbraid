package app.unbraid

import app.unbraid.data.Location
import app.unbraid.data.Route
import app.unbraid.data.WgPeer
import app.unbraid.vpn.ExitCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitCheckTest {
    private val trace = "fl=123f45\nh=www.cloudflare.com\nip=203.0.113.42\nts=1792875869.1\nvisit_scheme=https\nuag=unbraid\ncolo=AMS\nloc=NL\ntls=TLSv1.3\n"

    @Test fun readsTheAddressAndCountry() {
        assertEquals("203.0.113.42" to "NL", ExitCheck.parse(trace))
        assertEquals("2001:db8::1" to "DE", ExitCheck.parse(trace.replace("203.0.113.42", "2001:db8::1").replace("loc=NL", "loc=DE")))
    }

    /** Without a well-formed address there is no report; an unknown country still leaves the address. */
    @Test fun malformedTracesAreRefused() {
        assertNull(ExitCheck.parse(trace.replace("ip=203.0.113.42", "ip=999.1.1.1")))
        assertNull(ExitCheck.parse(trace.replace("ip=203.0.113.42", "ip=<b>hi</b>")))
        assertNull(ExitCheck.parse("<html>blocked</html>"))
        assertEquals("203.0.113.42" to null, ExitCheck.parse(trace.replace("loc=NL", "loc=XX")))
        assertEquals("203.0.113.42" to null, ExitCheck.parse(trace.replace("loc=NL", "loc=T1")))
    }

    @Test fun ipv6Addresses() {
        for (ok in listOf("2001:db8::1", "::1", "fe80::", "2001:0db8:0000:0000:0000:ff00:0042:8329")) assertEquals(ok, true, ExitCheck.ipv6(ok))
        for (bad in listOf("::::", "1:2", "1:2:3:4:5:6:7:8:9", "2001:db8::1::2", "2001:db8:::1", "12345::1", "g::1", "١::1")) assertEquals(bad, false, ExitCheck.ipv6(bad))
    }

    @Test fun countryCodes() {
        assertEquals("GB", ExitCheck.country("uk"))
        assertEquals("GB", ExitCheck.country(" GB "))
        assertNull(ExitCheck.country("··"))
        assertNull(ExitCheck.country(null))
        assertEquals("Netherlands", ExitCheck.name("NL"))
    }

    /** Only a route set to one country has one to compare with. */
    @Test fun expectedCountry() {
        assertEquals("IT", ExitCheck.expected(Route("it", Location("nord", 106, "IT", "Italy"))))
        assertEquals("GB", ExitCheck.expected(Route("uk", Location("surfshark", countryCode = "UK", country = "United Kingdom"))))
        assertNull(ExitCheck.expected(Route("f", Location("mullvad")))) // Fastest
        assertNull(ExitCheck.expected(Route("d", Location("nord", 106, "IT", "Italy", group = "legacy_double_vpn"))))
        assertNull(ExitCheck.expected(Route("o", Location("nord", 106, "IT", "Italy", group = "legacy_onion_over_vpn"))))
        assertNull(ExitCheck.expected(Route("ts", tsExit = "100.64.0.1")))
        assertNull(ExitCheck.expected(Route("wg", wg = WgPeer("Box", "k", listOf("10.0.0.2/32"), "p", endpoint = "x:1", allowedIps = listOf("0.0.0.0/0")))))
    }
}
