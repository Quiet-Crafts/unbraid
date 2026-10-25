package app.unbraid

import app.unbraid.vpn.ExitCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitCheckTest {
    private val trace = "fl=123f45\nh=www.cloudflare.com\nip=203.0.113.42\nts=1792875869.1\nvisit_scheme=https\nuag=unbraid\ncolo=AMS\nloc=NL\ntls=TLSv1.3\n"

    @Test fun readsTheAddress() {
        assertEquals("203.0.113.42", ExitCheck.parse(trace))
        assertEquals("2001:db8::1", ExitCheck.parse(trace.replace("203.0.113.42", "2001:db8::1")))
    }

    /** Without a well-formed address there is no report. */
    @Test fun malformedTracesAreRefused() {
        assertNull(ExitCheck.parse(trace.replace("ip=203.0.113.42", "ip=999.1.1.1")))
        assertNull(ExitCheck.parse(trace.replace("ip=203.0.113.42", "ip=<b>hi</b>")))
        assertNull(ExitCheck.parse("<html>blocked</html>"))
    }

    @Test fun ipv6Addresses() {
        for (ok in listOf("2001:db8::1", "::1", "fe80::", "2001:0db8:0000:0000:0000:ff00:0042:8329")) assertEquals(ok, true, ExitCheck.ipv6(ok))
        for (bad in listOf("::::", "1:2", "1:2:3:4:5:6:7:8:9", "2001:db8::1::2", "2001:db8:::1", "12345::1", "g::1", "١::1")) assertEquals(bad, false, ExitCheck.ipv6(bad))
    }
}
