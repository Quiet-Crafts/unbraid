package app.unbraid

import app.unbraid.data.Pia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class PiaTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.use { String(it.readBytes()) }
    private val list = res("pia_servers_v6.txt")

    @Test
    fun serverListWithSignatureTailParses() {
        assertTrue(list.substringAfter("\n").startsWith("\n"))
        val rs = Pia.parseRegions(list)
        assertEquals(listOf("us_denver", "nl_amsterdam", "nl_netherlands-so", "dz", "de_berlin", "de-frankfurt", "uk", "uk_manchester"), rs.map { it.id })
        val denver = rs.first { it.id == "us_denver" }
        assertEquals(listOf("denver425", "denver429", "denver431"), denver.servers.wg.map { it.cn })
        assertEquals("181.41.206.151", denver.servers.wg.first().ip)
        assertTrue(denver.country == "US" && !denver.geo && !denver.portForward)
        assertTrue(rs.first { it.id == "dz" }.geo)
    }

    @Test
    fun regionsWithoutWireguardOrOfflineAreSkipped() {
        val body = """{"regions":[{"id":"a","name":"A","country":"NL","servers":{"meta":[{"ip":"1.1.1.1","cn":"x"}]}},""" +
            """{"id":"b","name":"B","country":"NL","offline":true,"servers":{"wg":[{"ip":"1.1.1.2","cn":"y"}]}},""" +
            """{"id":"c","name":"C","country":"NL","servers":{"wg":[{"ip":"1.1.1.3","cn":"z"}]}}]}""" + "\n\nc2ln"
        assertEquals(listOf("c"), Pia.parseRegions(body).map { it.id })
    }

    @Test
    fun garbageListIsAnIoError() {
        try { Pia.parseRegions("<html>nope</html>"); fail() } catch (e: IOException) { }
    }

    @Test
    fun countriesAndCities() {
        val cs = Pia.countries(Pia.parseRegions(list))
        assertEquals(listOf("Algeria", "Germany", "Netherlands", "United Kingdom", "United States"), cs.map { it.name })
        val de = cs.first { it.code == "DE" }
        assertEquals(listOf("Berlin", "Frankfurt"), de.cities.map { it.name })
        assertEquals(listOf("de_berlin", "de-frankfurt"), de.cities.map { it.key })
        assertEquals(4, de.serverCount)
        val nl = cs.first { it.code == "NL" }
        assertEquals(listOf("Netherlands", "Netherlands Streaming Optimized"), nl.cities.map { it.name })
        // Ids come from the region id, so they survive PIA adding or dropping regions.
        assertEquals(nl.cities.map { app.unbraid.data.stableId(it.key!!) }, nl.cities.map { it.id })
        assertEquals(2, nl.cities.map { it.id }.toSet().size)
        assertEquals(cs.size, cs.map { it.id }.toSet().size)
    }

    @Test
    fun addKeyResponseParses() {
        val k = Pia.parseAddKey(res("pia_addkey.json"))
        assertEquals("eo8n2+EwTSaTW8R/q9uJ813QuBY8uaF+vccoM5WNDEs=", k.serverKey)
        assertEquals(1337, k.serverPort)
        assertEquals("10.30.128.2", k.peerIp)
        assertEquals(listOf("10.0.0.243", "10.0.0.242"), k.dns)
    }

    @Test
    fun addKeyErrorsAreIoErrors() {
        try { Pia.parseAddKey("""{"status":"ERROR","message":"Login failed!"}"""); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("Login failed!")) }
        try { Pia.parseAddKey("""{"status":"OK"}"""); fail() } catch (e: IOException) { }
        try { Pia.parseAddKey("not json"); fail() } catch (e: IOException) { }
    }

    @Test
    fun caLoadsIntoKeyStore() {
        val ks = Pia.caStore()
        assertEquals(listOf("pia"), ks.aliases().toList())
        val cert = ks.getCertificate("pia") as java.security.cert.X509Certificate
        assertTrue(cert.subjectX500Principal.name.contains("Private Internet Access"))
        assertNotNull(Pia.tls)
    }

    @Test
    fun accountBlobRoundTrips() {
        val a = Pia.Account("p1234567", "pw", "tok", 1L, "k1", "k2")
        assertEquals(a, Pia.account(kotlinx.serialization.json.Json.encodeToString(Pia.Account.serializer(), a)))
    }

    @Test
    fun parseFailuresDoNotQuoteTheReply() {
        for (f in listOf({ Pia.parseAddKey("""{"status":"OK","server_key":"SECRET-KEY","server_port":"nope" """) }, { Pia.parseRegions("""{"regions":"SECRET-LIST" """) }, { Pia.account("""{"username":"SECRET-USER" """) })) {
            try { f(); fail() } catch (e: IOException) { assertFalse(e.message!!.contains("SECRET")); assertNull(e.cause) }
        }
    }
}
