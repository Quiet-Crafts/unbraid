package app.unbraid.data

import app.unbraid.APP
import kotlinx.serialization.Serializable
import java.io.IOException
import kotlin.random.Random

/**
 * ExpressVPN over OpenVPN (its Lightway protocol is proprietary). There is no API to check the login, so sign-in
 * validates the format only and the first connection finds out. The account blob is the manual-configuration login.
 *
 * Servers: gluetun's catalogue (`expressvpn.json`, hostnames resolved to IPs; gluetun's updater has a hard-coded
 * hostname list, internal/provider/expressvpn/updater/hardcoded.go). Connection: UDP port 1195 only, no TCP
 * (internal/provider/expressvpn/connection.go: `NewConnectionDefaults(0, 1195, 0)`, updater: "no TCP support").
 * The profile is rebuilt from gluetun's internal/provider/expressvpn/openvpnconf.go (MIT, github.com/qdm12/gluetun):
 * CA bundle, shared client certificate and key, TLS-auth key with key-direction 1, `remote-cert-tls server`,
 * `verify-x509-name Server name-prefix`, data-ciphers AES-256-GCM:AES-256-CBC:AES-128-GCM (fallback AES-256-GCM),
 * auth SHA512, mssfix 1200, fragment 1300, fast-io, sndbuf/rcvbuf 524288, explicit-exit-notify, hand-window 20
 * (hand-window comes from gluetun's generic OpenVPN template, internal/provider/utils/openvpn.go).
 * Login location: ExpressVPN help "Manual config for Linux with OpenVPN"
 * (expressvpn.com/support/vpn-setup/manual-config-for-linux-with-openvpn): expressvpn.com/setup#manual, Manual
 * configuration, username and password under "Setup".
 * gluetun sets no compression; ExpressVPN's own downloadable profile has `comp-lzo no`, cipher AES-256-CBC and
 * `tun-mtu 1500`. sing-box applies a pushed cipher/compression like OpenVPN does, so gluetun's lines are kept;
 * `auth-retry nointeract` is left out so a wrong login fails instead of retrying.
 */
object ExpressVpn : Provider {
    @Serializable
    internal data class Account(val username: String, val password: String)

    private const val PORT = 1195
    private const val MAX_LOGIN = 256

    override val id = "expressvpn"
    override val name = "ExpressVPN"
    override val protocol = "OpenVPN"
    override val fields = listOf(
        Field("username", "Username", secret = false, hint = "manual-configuration username"),
        Field("password", "Password", hint = "manual-configuration password"),
    )
    override val help = "These are not your sign-in email and password. Sign in at expressvpn.com/setup#manual, open Advanced setup " +
        "(or Show other devices), choose Manual configuration, and copy the username and password shown on the right under Setup. " +
        "$APP connects with OpenVPN (UDP); ExpressVPN's Lightway protocol isn't supported."

    override fun signIn(input: Map<String, String>): String {
        val user = input["username"].orEmpty().trim()
        val pass = input["password"].orEmpty().trim()
        require(user.isNotEmpty() && pass.isNotEmpty()) { "Enter the manual-configuration username and password" }
        require(user.length <= MAX_LOGIN && pass.length <= MAX_LOGIN && (user + pass).none { it.isISOControl() || it.isWhitespace() }) {
            "That username or password has characters ExpressVPN's manual-configuration login doesn't use"
        }
        return NordApi.json.encodeToString(Account.serializer(), Account(user, pass))
    }

    internal fun account(blob: String) = Http.parsed("ExpressVPN") { NordApi.json.decodeFromString(Account.serializer(), blob) }

    override fun countries(account: String) = countries(fetch())

    override fun connect(loc: Location, account: String): Link = link(if (loc.pinned == null) fetch() else null, loc, account(account))

    override fun servers(loc: Location, account: String, limit: Int): List<Server> =
        Catalog.pick(fetch(), loc).take(limit).map { server(it, it.ips.filter(::isIpv4).random()) }

    private fun fetch() = Catalog.fetch("expressvpn", "openvpn").filter { it.udp }

    internal fun countries(entries: List<Catalog.Entry>): List<Country> = Catalog.countries(entries)

    internal fun link(entries: List<Catalog.Entry>?, loc: Location, a: Account, rnd: Random = Random): OvpnLink {
        val sv = loc.pinned ?: run {
            val e = Catalog.pick(entries!!, loc).firstOrNull() ?: throw IOException("No ExpressVPN server for ${loc.label}")
            server(e, e.ips.filter(::isIpv4).random(rnd))
        }
        return OvpnLink(sv, Manual.parseOvpn(profile(sv.ip), sv.name, a.username, a.password))
    }

    /** Strict dotted IPv4: catalogue text is interpolated into the OpenVPN profile, so nothing else may get through. */
    private fun isIpv4(s: String) = s.split('.').let { p -> p.size == 4 && p.all { it.length in 1..3 && it.all(Char::isDigit) && it.toInt() <= 255 } }

    private fun server(e: Catalog.Entry, ip: String) =
        Server(e.hostname.substringBefore('.').removeSuffix("-ca-version-2").trimEnd('-'), e.hostname, ip, "", 0, e.city.orEmpty(), Catalog.code(e.place))

    private fun pem(label: String, b64: String) = "-----BEGIN $label-----\n" + b64.chunked(64).joinToString("\n") + "\n-----END $label-----\n"

    /** The client profile for [ip], as gluetun writes it (see the class comment); the login is passed separately. */
    internal fun profile(ip: String): String = buildString {
        append("client\ndev tun\nproto udp\nremote $ip $PORT\nauth-user-pass\n")
        append("key-direction 1\nremote-cert-tls server\nverify-x509-name Server name-prefix\nfast-io\n")
        append("data-ciphers-fallback aes-256-gcm\ndata-ciphers aes-256-gcm:aes-256-cbc:aes-128-gcm\nauth sha512\n")
        append("mssfix 1200\nfragment 1300\nsndbuf 524288\nrcvbuf 524288\nexplicit-exit-notify\nhand-window 20\n")
        append("<ca>\n").append(CAS.joinToString("") { pem("CERTIFICATE", it) }).append("</ca>\n")
        append("<cert>\n").append(pem("CERTIFICATE", CERT)).append("</cert>\n")
        append("<key>\n").append(pem("RSA PRIVATE KEY", RSA_KEY)).append("</key>\n")
        append("<tls-auth>\n-----BEGIN OpenVPN Static key V1-----\n").append(TLS_AUTH.chunked(32).joinToString("\n"))
        append("\n-----END OpenVPN Static key V1-----\n</tls-auth>\n")
    }

    // Verbatim from gluetun internal/provider/expressvpn/openvpnconf.go (MIT, github.com/qdm12/gluetun); the same
    // certificate, key and TLS-auth key sit in every ExpressVPN profile.
    private const val CA1 = "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"
    private const val CA2 = "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"
    private val CAS = listOf(CA1, CA2)
    private const val CERT = "MIIDTjCCAregAwIBAgIDKzZvMA0GCSqGSIb3DQEBCwUAMIGFMQswCQYDVQQGEwJVUzELMAkGA1UECBMCQ0ExFTATBgNVBAcTDFNhbkZyYW5jaXNjbzEVMBMGA1UEChMMRm9ydC1GdW5zdG9uMRgwFgYDVQQDEw9Gb3J0LUZ1bnN0b24gQ0ExITAfBgkqhkiG9w0BCQEWEm1lQG15aG9zdC5teWRvbWFpbjAgFw0xNjExMDMwMzA2MThaGA8yMDY2MTEwMzAzMDYxOFowgYoxCzAJBgNVBAYTAlZHMQwwCgYDVQQIDANCVkkxEzARBgNVBAoMCkV4cHJlc3NWUE4xEzARBgNVBAsMCkV4cHJlc3NWUE4xHDAaBgNVBAMME2V4cHJlc3N2cG5fY3VzdG9tZXIxJTAjBgkqhkiG9w0BCQEWFnN1cHBvcnRAZXhwcmVzc3Zwbi5jb20wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQCrOYt/KOi2uMDGev3pXg8j1SO4J/4EVWDF7vJcKr2jrZlqD/zuAFx2W1YWvwumPO6PKH4PU9621aNdiumaUkv/RplCfznnnxqobhJuTE2oA+rS1bOq+9OhHwF9jgNXNVk+XX4d0toST5uGE6Z3OdmPBur8o5AlCf78PDSAwpFOw5HrgLqOEU4hTweC1/czX2VsvsHv22HRI6JMZgP8gGQii/p9iukqfaJvGdPciL5p1QRBUQIi8P8pNvEp1pVIpxYj7/LOUqb2DxFvgmp2v1IQ0Yu88SWsFk84+xAYHzfkLyS31Sqj5uLRBnJqx3fIlOihQ50GI72fwPMwo+OippvVAgMBAAGjPzA9MAwGA1UdEwEB/wQCMAAwDgYDVR0PAQH/BAQDAgSwMB0GA1UdDgQWBBSkBM1TCX9kBgFsv2RmOzudMXa9njANBgkqhkiG9w0BAQsFAAOBgQA+2e4b+33zFmA+1ZQ46kWkfiB+fEeDyMwMLeYYyDS2d8mZhNZKdOw7dy4Ifz9Vqzp4aKuQ6j61c6k1UaQQL0tskqWVzslSFvs9NZyUAJLLdGUc5TT2MiLwiXQwd4UvH6bGeePdhvB4+ZbW7VMD7TE8hZhjhAL4F6yAP1EQvg3LDA=="
    private const val RSA_KEY = "MIIEpAIBAAKCAQEAqzmLfyjotrjAxnr96V4PI9UjuCf+BFVgxe7yXCq9o62Zag/87gBcdltWFr8Lpjzujyh+D1PettWjXYrpmlJL/0aZQn85558aqG4SbkxNqAPq0tWzqvvToR8BfY4DVzVZPl1+HdLaEk+bhhOmdznZjwbq/KOQJQn+/Dw0gMKRTsOR64C6jhFOIU8Hgtf3M19lbL7B79th0SOiTGYD/IBkIov6fYrpKn2ibxnT3Ii+adUEQVECIvD/KTbxKdaVSKcWI+/yzlKm9g8Rb4Jqdr9SENGLvPElrBZPOPsQGB835C8kt9Uqo+bi0QZyasd3yJTooUOdBiO9n8DzMKPjoqab1QIDAQABAoIBAHgsekC0SKi+AOcNOZqJ3pxqophE0V7fQX2KWGXhxZnUZMFxGTc936deMYzjZ1y0lUa6x8cgOUcfqHol3hDmw9oWBckLHGv5Wi9umdb6DOLoZO62+FQATSdfaJ9jheq2Ub2YxsRN0apaXzB6KDKz0oM0+sZ4Udn9Kw6DfuIELRIWwEx4w0v3gKW7YLC4Jkc4AwLkPK03xEA/qImfkCmaMPLhrgVQt+IFfP8bXzL7CCC04rNU/IS8pyjex+iUolnQZlbXntF7Bm4V2mz0827ZVqrgAb/hEQRlsTW3rRkVh+rrdoUE7BCZRTFmRCbLoShjN6XuSf4sAus8ch4UEN12gN0CgYEA4o/tSvij1iPaXLmt4KOEuxqmSGB8MLKhFde8lBbNdrDgxiIH9bH7khKx15XRTX0qLDbs8b2/UJygZG0Aa1kIBqZTXTgeMAuxPRTesALJPdqQ/ROnbJcdFkI7gllrAG8VB0fH4wTRsRd0vWEB6YlCdE107u6LEsLAHxOj9Q5819cCgYEAwXjx9RkQ2qITBx5Ewib8YsltA0n3cmRomPicLlsnKV5DfvyCLpFIsZ1h3f9dUpfxRLwzp8wcoLiq9cCoOGdu1udw/yBTqmhaXWhUK/g77f9Ze2ZB1OEhuyKLYJ1vW/h/Z/a1aPCMxZqsDTPCePsuO8Cez5gqs8LjM3W7EyzRxDMCgYEAvhHrDFt975fSiLoJgo0MPIAGAnBXn+8sLwv3m/FpW+rWF8LTFK/Fku12H5wDpNOdvswxijkauIE+GiJMGMLvdcyx4WHECaC1h73reJRNykOEIZ0Md5BrCZJ1JEzp9Mo8RQhWTEFtvfkkqgApP4g0pSeaMx0StaGG1kt+4IbP+68CgYBrZdQKlquAck/Vt7u7eyDHRcE5/ilaWtqlb/xizz7h++3D5C/v4b5UumTFcyg+3RGVclPKZcfOgDSGzzeSd/hTW46iUTOgeOUQzQVMkzPRXdoyYgVRQtgSpY5xR3O1vjAbahwx8LZ0SvQPMBhYSDbV/Isr+fBacWjl/AipEEwxeQKBgQDdrAEnVlOFoCLw4sUjsPoxkLjhTAgI7CYk5NNxX67Rnj0tp+Y49+sGUhl5sCGfMKkLShiON5P2oxZa+B0aPtQjsdnsFPa1uaZkK4c++SS6AetzYRpVDLmLp7/1CulE0z3O0sBekpwiuaqLJ9ZccC81g4+2j8j6c50rIAct3hxIxw=="
    private const val TLS_AUTH = "48d9999bd71095b10649c7cb471c1051b1afdece597cea06909b99303a18c67401597b12c04a787e98cdb619ee960d90a0165529dc650f3a5c6fbe77c91c137dcf55d863fcbe314df5f0b45dbe974d9bde33ef5b4803c3985531c6c23ca6906d6cd028efc8585d1b9e71003566bd7891b9cc9212bcba510109922eed87f5c8e66d8e59cbd82575261f02777372b2cd4ca5214c4a6513ff26dd568f574fd40d6cd450fc788160ff68434ce2bf6afb00e710a3198538f14c4d45d84ab42637872e778a6b35a124e700920879f1d003ba93dccdb953cdf32bea03f365760b0ed8002098d4ce20d045b45a83a8432cc737677aed27125592a7148d25c87fdbe0a3f6"
}
