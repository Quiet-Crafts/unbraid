package app.unbraid

import app.unbraid.vpn.Redact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * A wide set of made-up secrets in the shapes providers, the engine and the app produce, run through [Redact.clean].
 * Some shapes are known to get through (free echoes, split keys, prose); this pins how many at most, that nothing
 * crashes, and that hostile inputs finish in bounded time. Every secret carries a marker ("witness") so survival is
 * detected mechanically. `real` = the app, a provider or the engine can really produce this text. Nothing is printed.
 */
class RedactCorpusTest {
    private class Case(val id: String, val shape: String, val input: String, val witnesses: List<String>, val real: Boolean)

    private val cases = ArrayList<Case>()
    private fun add(shape: String, input: String, witnesses: List<String>, real: Boolean = true) {
        cases += Case("%03d".format(cases.size + 1), shape, input, witnesses, real)
    }
    private fun add(shape: String, input: String, witness: String, real: Boolean = true) = add(shape, input, listOf(witness), real)

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun build() {
        val b64 = Base64.getEncoder()
        val key = b64.encodeToString(ByteArray(32) { (it * 7 + 3).toByte() })
        val slashKey = b64.encodeToString(ByteArray(32) { if (it % 2 == 0) 251.toByte() else 255.toByte() })
        // 1. WireGuard keys in all encodings and surroundings
        for ((name, k) in listOf("standard" to key, "slash" to slashKey, "url-safe" to slashKey.replace('+', '-').replace('/', '_'))) {
            for (pad in listOf(true, false)) {
                val v = if (pad) k else k.replace("=", "")
                val tag = "wg-$name-${if (pad) "padded" else "raw"}"
                val a = v.take(20); val bb = v.drop(20)
                for ((shape, input) in listOf("bare" to v, "adjacent" to "otherToken$v", "field" to "private_key=$v",
                    "url-path" to "https://invalid.example/$v/end", "query" to "https://invalid.example/?pt=$v end",
                    "split" to "$a\n$bb", "split-field" to "PrivateKey=$a\n$bb", "continued" to "PrivateKey=$a\\\n$bb")) add("$tag-$shape", input, listOf(a, bb))
                var raw = v; var field = "{\"private_key\":\"$v\"}"; var wit = a
                for (d in 1..3) {
                    raw = q(raw); field = q(field); wit = q(wit).drop(1).dropLast(1)
                    add("$tag-json-bare-$d", raw, listOf(a, bb, wit)); add("$tag-json-field-$d", field, listOf(a, bb))
                }
                val esc = v.replace("/", "\\/")
                add("$tag-escaped-slash", "bad key \"$esc\"", listOf(esc.take(18), esc.takeLast(18)))
                val uni = v.map { "\\u%04x".format(it.code) }.joinToString("")
                add("$tag-unicode-bare", "bad key \"$uni\"", uni.take(24), real = false)
                add("$tag-unicode-field", "private_key=\"$uni\"", uni.take(24), real = false)
            }
        }
        val short = "SyntheticPass7"; val nord = "SyntheticNordToken0123456789Abcde"
        // 2. Field names (providers' JSON/forms)
        for (f in listOf("password", "passwd", "token", "access_token", "session_token", "sessionToken", "nordlynx_private_key", "auth_key", "api_key", "psk", "credentials",
            "credential", "secret", "pass", "pt", "key", "account_number", "account_id", "refresh_token", "id_token", "apiKey", "api-key", "secret_key", "private-key",
            "passphrase", "pre_shared_key", "preshared_key", "client_secret", "authkey", "bearer", "sessionid", "cookie", "username", "user", "uuid", "pwd", "auth", "otp"))
            add("field-json-$f", "{\"$f\":\"$short\"}", short, real = f !in listOf("passwd", "pwd", "otp", "authkey", "bearer"))
        for (f in listOf("password", "token", "api_key", "private_key", "api-key", "secret_key", "private-key", "passphrase", "refresh_token", "access_token", "pre_shared_key"))
            for (sep in listOf(":", "=", " : ", "\t=\t", " = ", ": ", " "))
                add("sep-$f-${sep.replace("\t", "TAB").replace(" ", "_")}", f + sep + short, short, real = sep != "\t=\t")
        // 3. Authorization headers
        for ((n, i, m) in listOf(
            Triple("bearer-short", "Authorization: Bearer $short", short), Triple("bearer-nord", "Authorization: Bearer $nord", nord),
            Triple("bearer-long", "Authorization: Bearer " + "Z".repeat(50), "Z".repeat(20)),
            Triple("bearer-quoted", "Authorization: \"Bearer $short\"", short), Triple("bearer-json", "{\"Authorization\":\"Bearer $short\"}", short),
            Triple("bearer-lower", "authorization: bearer $short", short), Triple("bearer-nocolon", "Bearer $short", short),
            Triple("basic-short", "Authorization: Basic " + b64.encodeToString("x:FakePass7".toByteArray()), b64.encodeToString("x:FakePass7".toByteArray())),
            Triple("basic-quoted", "Authorization: \"Basic " + b64.encodeToString("x:FakePass7".toByteArray()) + "\"", b64.encodeToString("x:FakePass7".toByteArray())),
            Triple("basic-bare", "Basic " + b64.encodeToString("fakeuser:FakePass7".toByteArray()), b64.encodeToString("fakeuser:FakePass7".toByteArray())),
            Triple("x-api-key-hdr", "X-Api-Key: $short", short), Triple("x-auth-token-hdr", "X-Auth-Token: $short", short),
            Triple("cookie-hdr", "Cookie: session=$short; path=/", short), Triple("set-cookie-hdr", "Set-Cookie: sid=$short; HttpOnly", short),
            Triple("proxy-auth", "Proxy-Authorization: Basic " + b64.encodeToString("u:FakePass7".toByteArray()), b64.encodeToString("u:FakePass7".toByteArray())),
        )) add(n, i, m, real = true)
        // 4. Provider shapes
        add("pia-url", "https://192.0.2.20:1337/addKey?pt=$short&pubkey=dummy", short)
        add("pia-url-encoded", "https://192.0.2.20:1337/addKey?pt=Fake%2FPass%2B7 end", "Fake%2FPass%2B7")
        add("pia-username", "login failed for p1234567", "p1234567")
        add("pia-user-pass-json", "{\"username\":\"p1234567\",\"password\":\"$short\"}", listOf("p1234567", short))
        add("nord-raw-token", "rejected $nord", nord)
        add("nord-token-64hex", "token e9f2" + "a1".repeat(30) + "bd", "a1a1a1a1a1a1a1a1")
        add("mullvad-account-field", "account_number=1234567890123456", "1234567890123456")
        add("mullvad-account-bare", "invalid account 1234567890123456", "1234567890123456")
        add("mullvad-account-spaced", "account 1234 5678 9012 3456 not found", "5678 9012")
        add("mullvad-account-json", "{\"account\":\"1234567890123456\"}", "1234567890123456")
        add("mullvad-url", "https://api.mullvad.net/accounts/v1/accounts/1234567890123456", "1234567890123456")
        add("ivpn-account-field", "account_id=i-SYNTH-1234-5678", "i-SYNTH-1234-5678")
        add("ivpn-account-bare", "unknown account i-SYNT-1234-5678", "i-SYNT-1234-5678")
        add("ivpn-account-json", "{\"account_id\":\"i-SYNT-1234-5678\"}", "i-SYNT-1234-5678")
        add("ivpn-account-url", "https://api.ivpn.net/v5/session/new?id=i-SYNT-1234-5678", "i-SYNT-1234-5678")
        add("ivpn-legacy-ivpnxxxx", "account ivpnSYNT1234 failed", "ivpnSYNT1234", real = false)
        add("airvpn-api-key-field", "api_key=" + "c0ffee".repeat(5) + "ab", "c0ffeec0ffee")
        add("airvpn-key-bare32hex", "key " + "c0ffee".repeat(5) + "ab bad", "c0ffeec0ffee")
        add("airvpn-key-in-url", "https://airvpn.org/api/?key=" + "c0ffee".repeat(5) + "ab&service=userinfo", "c0ffeec0ffee")
        add("airvpn-device-key", "device key " + "c0ffee".repeat(10) + " rejected", "c0ffeec0ffee")
        add("proton-session-uid", "{\"UID\":\"synthuid0123456789abcdef\",\"AccessToken\":\"SynthAccess0123456789abcdef\"}", listOf("synthuid0123456789abcdef", "SynthAccess0123456789abcdef"))
        add("proton-password-field", "{\"Password\":\"$short\"}", short)
        add("nextdns-profile", "https://dns.nextdns.io/abc123", "abc123")
        // 5. Tailscale
        add("tskey-auth", "invalid key tskey-auth-kSYNTH1CNTRL-AbCdEfGhIjKlMnOpQrStUvWxYz012345", "AbCdEfGhIjKl")
        add("tskey-auth-short", "tskey-auth-kSYNTH1CNTRL-AbCd1234", "AbCd1234")
        add("tskey-api", "tskey-api-kSYNTH1CNTRL-AbCdEfGhIjKlMnOpQrStUvWxYz012345", "AbCdEfGhIjKl")
        add("tskey-in-flag", "--auth-key=tskey-auth-kSYNTH1CNTRL-AbCd1234", "AbCd1234")
        add("tskey-json", "{\"AuthKey\":\"tskey-auth-kSYNTH1CNTRL-AbCd1234\"}", "AbCd1234")
        add("ts-login-url", "To authenticate, visit: https://login.tailscale.com/a/abc123synth456", "abc123synth456")
        add("ts-nodekey", "nodekey:" + "ab".repeat(32), "abababababab")
        add("ts-privatenodekey", "privkey:" + "cd".repeat(32), "cdcdcdcdcdcd")
        add("ts-machinekey", "mkey:" + "ef".repeat(32), "efefefefefef")
        add("headscale-authkey", "authkey=" + "12ab".repeat(12), "12ab12ab12ab")
        // 6. WireGuard config text
        for ((n, i) in listOf("wg-conf-privatekey-spaced" to "PrivateKey = $key", "wg-conf-privatekey-nospace" to "PrivateKey=$key", "wg-conf-privatekey-lower" to "privatekey = $key",
            "wg-conf-presharedkey" to "PresharedKey = $key", "wg-conf-presharedkey-lower" to "presharedkey=$key", "wg-conf-publickey" to "PublicKey = $key")) add(n, i, listOf(key.take(20), key.drop(20)), real = n != "wg-conf-publickey")
        add("wg-conf-full", "[Interface]\nPrivateKey = $key\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = ${slashKey}\nPresharedKey = ${key.reversed()}\nEndpoint = vpn.example:51820", listOf(key.take(20), key.reversed().take(20)))
        add("wg-conf-crlf", "[Interface]\r\nPrivateKey = $key\r\nAddress = 10.0.0.2/32", listOf(key.take(20), key.drop(20)))
        add("wg-conf-tab-indented", "\tPrivateKey\t=\t$key", listOf(key.take(20), key.drop(20)))
        add("wg-conf-split-2lines", "PrivateKey = ${key.take(20)}\n${key.drop(20)}", listOf(key.take(20), key.drop(20)))
        add("wg-conf-split-2lines-json", "{\"private_key\":\"${key.take(20)}\",\n\"x\":\"${key.drop(20)}\"}", listOf(key.take(20), key.drop(20)), real = false)
        add("json-private_key", "{\"private_key\":\"$key\"}", listOf(key.take(20), key.drop(20)))
        add("json-pre_shared_key", "{\"pre_shared_key\":\"$key\"}", listOf(key.take(20), key.drop(20)))
        add("json-private_key-escaped", "{\\\"private_key\\\":\\\"$key\\\"}", listOf(key.take(20), key.drop(20)))
        add("json-pre_shared_key-escaped", "{\\\"pre_shared_key\\\":\\\"$key\\\"}", listOf(key.take(20), key.drop(20)))
        add("singbox-wg-endpoint", "{\"type\":\"wireguard\",\"private_key\":\"$key\",\"peers\":[{\"public_key\":\"$slashKey\",\"pre_shared_key\":\"${key.reversed()}\"}]}", listOf(key.take(20), key.reversed().take(20)))
        add("singbox-decode-error", "decode config at outbounds[2].private_key: illegal base64 data at input byte 4: $key", listOf(key.take(20), key.drop(20)))
        add("singbox-wg-bad-key", "initialize endpoint[wg]: parse private_key: invalid key \"$key\"", listOf(key.take(20), key.drop(20)))
        add("hex-wg-key", "private_key=" + "ab".repeat(32), "abababababab", real = false)
        add("key-without-fieldname-44", "invalid $key here", listOf(key.take(20), key.drop(20)))
        add("key-ending-pad-eq-eq", "x " + b64.encodeToString(ByteArray(31) { (it * 5 + 1).toByte() }) + " y", b64.encodeToString(ByteArray(31) { (it * 5 + 1).toByte() }).take(20), real = false)
        // 7. OpenVPN
        val blk = listOf("MIIEvQIBADANBgkqhkiG9w0BAQEFAASC", "SYNTHKEYLINE2aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "SYNTHKEYLINE3bbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        add("ovpn-key-block", "<key>\n-----BEGIN PRIVATE KEY-----\n${blk.joinToString("\n")}\n-----END PRIVATE KEY-----\n</key>", listOf(blk[1], blk[2]))
        add("ovpn-key-block-oneline", "<key>-----BEGIN PRIVATE KEY----- ${blk.joinToString(" ")} -----END PRIVATE KEY-----</key>", listOf(blk[1], blk[2]))
        add("ovpn-key-block-crlf", "<key>\r\n${blk.joinToString("\r\n")}\r\n</key>", listOf(blk[1], blk[2]))
        add("ovpn-ec-key", "-----BEGIN EC PRIVATE KEY-----\nSYNTHECKEYLINEaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n-----END EC PRIVATE KEY-----", "SYNTHECKEYLINE")
        add("ovpn-tls-auth", "<tls-auth>\n-----BEGIN OpenVPN Static key V1-----\n" + "0123456789abcdef0123456789abcdef\n".repeat(3) + "-----END OpenVPN Static key V1-----\n</tls-auth>", "0123456789abcdef")
        add("ovpn-tls-crypt", "<tls-crypt>\n-----BEGIN OpenVPN Static key V1-----\nfedcba9876543210fedcba9876543210\nfedcba9876543210fedcba9876543211\n-----END OpenVPN Static key V1-----\n</tls-crypt>", "fedcba9876543210")
        add("ovpn-tls-crypt-v2", "-----BEGIN OpenVPN tls-crypt-v2 client key-----\nSYNTHV2CLIENTKEYAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n-----END OpenVPN tls-crypt-v2 client key-----", "SYNTHV2CLIENTKEY")
        add("ovpn-static-key-hex", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "0123456789abcdef", real = false)
        add("ovpn-static-key-line", "e7d1c4a9b2f03856e7d1c4a9b2f03856", "e7d1c4a9b2f0")
        add("ovpn-auth-user-pass-inline", "<auth-user-pass>\nfakeuser\n$short\n</auth-user-pass>", listOf("fakeuser", short))
        add("ovpn-auth-user-pass-two-lines", "auth-user-pass\nfakeuser\n$short", listOf(short))
        add("ovpn-auth-user-pass-line", "auth-user-pass fakeuser $short", listOf(short), real = false)
        add("ovpn-askpass", "askpass $short", short, real = false)
        add("ovpn-http-proxy-auth", "http-proxy-user-pass fakeuser $short", short)
        add("ovpn-remote-secret-port", "Invalid remote port: $short", short)
        add("ovpn-bad-cipher", "Unsupported cipher in cipher: $short", short, real = false)
        add("ovpn-unsupported-option", "Unsupported OpenVPN option: $short", short, real = false)
        add("ovpn-pkcs12-b64", "<pkcs12>\n" + "MIIKSYNTH".repeat(8) + "\n</pkcs12>", "MIIKSYNTHMIIKSYNTH", real = false)
        add("ovpn-secret-block", "<secret>\n$short$short\n</secret>", short)
        add("ovpn-pem-header-only", "-----BEGIN ENCRYPTED PRIVATE KEY-----\nSYNTHENCLINE1\n-----END ENCRYPTED PRIVATE KEY-----", "SYNTHENCLINE1")
        add("ovpn-passphrase-line", "passphrase=$short", short)
        add("ovpn-passphrase-spaced", "passphrase = $short $short", short)
        // 8. URLs with credentials
        val ssUser = b64.encodeToString("chacha20-ietf-poly1305:$short".toByteArray())
        for ((n, i, m) in listOf(
            Triple("ss-sip002", "ss://$ssUser@192.0.2.9:8388#name", ssUser), Triple("ss-legacy", "ss://" + b64.encodeToString("aes-256-gcm:$short@192.0.2.9:8388".toByteArray()), b64.encodeToString("aes-256-gcm:$short@192.0.2.9:8388".toByteArray())),
            Triple("ss-plain-userinfo", "ss://aes-256-gcm:$short@192.0.2.9:8388", short), Triple("ss-plugin", "ss://$ssUser@h:1/?plugin=v2ray-plugin%3Bmode%3Dwebsocket#x", ssUser),
            Triple("vless-uuid", "vless://12345678-1234-1234-1234-1234567890ab@h.example:443?type=ws#n", "12345678-1234-1234-1234-1234567890ab"),
            Triple("vless-uuid-bare", "bad uuid 12345678-1234-1234-1234-1234567890ab", "12345678-1234-1234-1234-1234567890ab"),
            Triple("uuid-field", "uuid=12345678-1234-1234-1234-1234567890ab", "12345678-1234-1234-1234-1234567890ab"),
            Triple("uuid-json", "{\"uuid\":\"12345678-1234-1234-1234-1234567890ab\"}", "12345678-1234-1234-1234-1234567890ab"),
            Triple("trojan-pass", "trojan://$short@h.example:443?sni=x#n", short), Triple("trojan-pass-special", "trojan://Syn%2Fth@h.example:443", "Syn%2Fth"),
            Triple("socks5-userpass", "socks5://fakeuser:$short@192.0.2.9:1080", short), Triple("socks5h-userpass", "socks5h://fakeuser:$short@192.0.2.9:1080", short),
            Triple("https-userpass-path", "https://fakeuser:$short@host.example/path?q=1", short),
            Triple("https-pass-at", "https://fakeuser:Syn@thetic1@host.example/path", "thetic1"),
            Triple("https-pass-slash", "https://fakeuser:Syn/thetic2@host.example/path", "thetic2"),
            Triple("https-pass-hash", "https://fakeuser:Syn#thetic3@host.example/path", "thetic3"),
            Triple("https-pass-colon", "https://fakeuser:Syn:thetic4@host.example/path", "thetic4"),
            Triple("https-pass-space", "https://fakeuser:Syn thetic5@host.example/path", "thetic5"),
            Triple("https-pass-pct-space", "https://fakeuser:Syn%20thetic6@host.example/path", "thetic6"),
            Triple("https-pass-question", "https://fakeuser:Syn?thetic7@host.example/path", "thetic7"),
            Triple("http-user-only", "http://SyntheticUser8@host.example/", "SyntheticUser8"),
            Triple("ftp-userpass", "ftp://fakeuser:$short@host.example/", short),
            Triple("wss-userpass", "wss://fakeuser:$short@host.example/ws", short),
            Triple("url-query-token", "https://host.example/api?token=$short&x=1", short), Triple("url-query-access_token", "https://host.example/api?access_token=$short", short),
            Triple("url-query-apikey", "https://host.example/api?apikey=$short", short), Triple("url-query-api_key", "https://host.example/api?api_key=$short&a=b", short),
            Triple("url-query-key", "https://host.example/api?key=$short", short), Triple("url-query-pwd", "https://host.example/api?pwd=$short", short),
            Triple("url-query-sig", "https://host.example/api?signature=$short", short), Triple("url-fragment-token", "https://host.example/cb#access_token=$short&x=1", short),
            Triple("url-path-token", "https://host.example/v1/tokens/$short/refresh", short),
            Triple("go-url-error", "Get \"https://fakeuser:$short@host.example/x\": dial tcp: lookup host.example: no such host", short),
            Triple("go-url-error-redacted-xxx", "Get \"https://fakeuser:xxxxx@host.example/x?token=$short\": EOF", short),
        )) add(n, i, m)
        // 9. Key/value variants
        for ((n, i, m) in listOf(
            Triple("token=", "token=$short", short), Triple("access_token", "access_token=$short", short), Triple("refresh_token", "refresh_token=$short", short),
            Triple("api-key", "api-key: $short", short), Triple("api_key", "api_key: $short", short), Triple("apikey", "apikey=$short", short), Triple("apiKey-camel", "apiKey: $short", short),
            Triple("secret_key", "secret_key=$short", short), Triple("private-key", "private-key=$short", short), Triple("passphrase", "passphrase=$short", short),
            Triple("password-spaces", "password=SyntheticFirst SyntheticSecond SyntheticThird", "SyntheticSecond"),
            Triple("password-spaces-quoted", "password=\"SyntheticFirst SyntheticSecond SyntheticThird\"", "SyntheticThird"),
            Triple("password-single-quoted-spaces", "password='SyntheticFirst SyntheticSecond'", "SyntheticSecond"),
            Triple("password-space-word", "password SyntheticFirst SyntheticSecond", "SyntheticFirst"),
            Triple("password-colon-space-phrase", "password: Synthetic First Phrase", "First"),
            Triple("password-escaped-newline", "password=SyntheticFirst\\nSyntheticSecond", "SyntheticSecond"),
            Triple("password-literal-newline", "password=SyntheticFirst\nSyntheticSecond", "SyntheticSecond"),
            Triple("password-json-escaped-quote", "{\"password\":\"Syn\\\"thetic9\"}", "thetic9"),
            Triple("password-quoted-key-json", "\"password\": \"$short\"", short),
            Triple("password-in-brackets", "[password=$short]", short), Triple("password-paren", "(password: $short)", short),
            Triple("password-comma-list", "user=fakeuser, password=$short, host=x", short),
            Triple("password-equals-in-value", "password=Syn=thetic10", "thetic10"),
            Triple("pass-short-name", "pass=$short", short), Triple("passwd-name", "passwd=$short", short), Triple("pwd-name", "pwd=$short", short),
            Triple("secret-name", "secret=$short", short), Triple("client_secret", "client_secret=$short", short),
            Triple("Secret-Capital", "Secret: $short", short), Triple("PASSWORD-caps", "PASSWORD=$short", short),
            Triple("user-and-pass-form", "username=fakeuser&password=$short", short), Triple("form-urlencoded-pass", "login=fakeuser&pass=Fake%2FPass%2B7&x=1", "Fake%2FPass%2B7"),
            Triple("dsn-style", "postgres://u:$short@h/db", short), Triple("env-style", "VPN_PASSWORD=$short", short), Triple("env-style-token", "NORD_TOKEN=$short", short),
            Triple("camelCase-privateKey", "privateKey=$short", short), Triple("camelCase-preSharedKey", "preSharedKey=$short", short),
            Triple("json-camel-privateKey", "{\"privateKey\":\"$short\"}", short),
            Triple("kotlin-toString", "WgPeer(name=x, privateKey=$short, publicKey=pub)", short), Triple("kotlin-toString-ovpn", "OvpnProfile(user=fakeuser, pass=$short)", short),
            Triple("kotlin-toString-ss", "SsProfile(method=aes, password=$short, host=h)", short), Triple("kotlin-toString-token", "Account(provider=x, secret=$short)", short),
            Triple("kotlin-toString-userfield", "Account(username=SyntheticLogin11)", "SyntheticLogin11"),
            Triple("json-array-secret", "{\"secrets\":[\"$short\"]}", short),
            Triple("json-nested-token", "{\"data\":{\"auth\":{\"token\":\"$short\"}}}", short),
        )) add(n, i, m)
        // 10. Go / engine errors that echo credentials
        for ((n, i, m) in listOf(
            Triple("go-parse-port", "parse \"ss://aes:pw@h:$short\": invalid port \":$short\" after host", short),
            Triple("go-parse-port-nord", "parse \"https://h:$short/\": invalid port \":$short\" after host", short),
            Triple("go-json-unexpected", "invalid character '$short' looking for beginning of value", short),
            Triple("go-json-field", "json: cannot unmarshal string into Go struct field Wg.private_key of type int: \"$short\"", short),
            Triple("go-base64", "illegal base64 data at input byte 4 in \"$short\"", short),
            Triple("go-hex", "encoding/hex: invalid byte: U+0053 'S' in \"$short\"", short),
            Triple("go-dial", "dial tcp: lookup $short: no such host", short, ),
            Triple("go-plugin-mode", "initialize outbound[1]: v2ray-plugin: unknown mode: password=SyntheticFirst SyntheticSecond", "SyntheticSecond"),
            Triple("go-unknown-method", "initialize outbound[0]: unknown method: $short", short),
            Triple("go-wireguard-uapi", "private_key=" + "ab".repeat(32) + "\npublic_key=" + "cd".repeat(32), "abababababab"),
            Triple("go-wireguard-uapi-psk", "preshared_key=" + "ab".repeat(32), "abababababab"),
            Triple("go-uapi-public-only", "public_key=" + "cd".repeat(32), "cdcdcdcdcdcd"),
            Triple("go-http-401", "Post \"https://api.example/v1\": 401 Unauthorized: {\"error\":\"bad token $short\"}", short),
            Triple("go-http-body", "unexpected status 400: {\"message\":\"account $short not found\"}", short),
            Triple("go-tls-error", "tls: failed to verify certificate: x509: certificate is valid for $short, not h", short),
            Triple("go-panic-line", "panic: runtime error: slice bounds out of range [:$short]", short),
            Triple("go-struct-dump", "&{Name:x PrivateKey:$short Peers:[]}", short),
            Triple("go-map-dump", "map[password:$short user:fakeuser]", short),
            Triple("go-struct-dump-token", "{Token:$short Expires:0}", short),
            Triple("go-wrapped-error", "start service: initialize inbound[0]: ss://aes:$short@h:1: bad", short),
            Triple("kotlin-npe", "java.lang.IllegalArgumentException: Invalid key $short", short, ),
            Triple("kotlin-parse-uri", "java.net.URISyntaxException: Illegal character in authority at index 8: https://fakeuser:Syn thetic12@h/", "thetic12"),
            Triple("kotlin-parse-uri2", "Illegal character in query at index 20: https://h/?token=Syn thetic13", "thetic13"),
            Triple("kotlin-numberformat", "java.lang.NumberFormatException: For input string: \"$short\"", short),
            Triple("kotlin-numberformat-pw", "For input string: \"Syn thetic14\"", "thetic14"),
            Triple("kotlin-serialization", "Unexpected JSON token at offset 12: Expected quotation mark, but had '$short' at path: \$.password", short),
            Triple("kotlin-serialization-input", "JSON input: {\"password\":\"$short\"}", short),
            Triple("kotlin-key-index", "Encountered unknown key 'token' at offset 3 JSON input: {\"token\":\"$short\"}", short),
            Triple("kotlin-http-status-body", "HTTP 403: {\"detail\":\"invalid token $short\"}", short),
            Triple("kotlin-regex-error", "Invalid WireGuard key for $short", short),
            Triple("kotlin-config-name-user-typed", "Invalid WireGuard key for My Home $short", short),
        )) add(n, i, m, real = !n.startsWith("kotlin-regex") && !n.startsWith("kotlin-config"))
        // 11. IP and e-mail (the code does not claim to hide them)
        for (a in listOf("192.0.2.17", "192.0.2.17:443", "198.51.100.8/24", "2001:db8::17", "[2001:db8::17]:443", "fe80::17%en0", "::ffff:192.0.2.17", "203.0.113.5"))
            add("ip-$a", "dial $a failed", a)
        for (v in listOf("synthetic.person@example.invalid", "synthetic+tag@example.invalid", "δοκιμή@example.invalid", "someone.synthetic@example.invalid"))
            add("email-$v", "login $v rejected", v)
        add("email-in-field", "email=synthetic.person@example.invalid", "synthetic.person")
        add("email-in-url-userinfo", "https://synthetic.person%40example.invalid:$short@h/", listOf("synthetic.person", short))
        add("path-users", "open /Users/synthetic-user/Library/file: denied", "synthetic-user", real = false)
        add("path-data-data", "open /data/user/0/app.unbraid/files/synthetic-user.json: no such file", "synthetic-user")
        // 12. Long lines and truncation
        add("long-secret-start", "password=$short " + "benign ".repeat(30000), short)
        add("long-secret-end", "benign ".repeat(30000) + "password=$short", short)
        add("long-secret-at-170", "x".repeat(10) + " ".repeat(160) + "password=$short", short)
        add("long-unclosed-quote", "password=\"$short" + "'\\".repeat(100000), short)
        add("long-key-block-1mb", "PrivateKey = " + "A".repeat(1_000_000), "AAAAAAAAAAAA")
        add("long-nonbase64-secret", "password=" + "é".repeat(5000) + short, short, real = false)
        add("secret-within-blob-run", "tok " + "B".repeat(39) + short + "B".repeat(39), short, real = false)
        add("blob-39-chars", "k=" + "C".repeat(39) + "SynthMark39", "SynthMark39", real = false)
        add("base64-40-with-dot", "tok " + "D".repeat(30) + "." + "SynthDot40".repeat(2), "SynthDot40")
        add("jwt", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJTeW50aGV0aWMifQ.SynthSigSynthSigSynthSig", "SynthSig")
        add("jwt-bare", "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJTeW50aGV0aWMifQ.SynthSigSynthSigSynthSig", "SynthSig")
        // 13. Unicode look-alikes and encodings
        for ((n, i) in listOf("unicode-long-s" to "pa\u017f\u017fword=$short", "unicode-cyrillic-a" to "p\u0430ssword=$short", "unicode-fullwidth" to "\uff50\uff41\uff53\uff53\uff57\uff4f\uff52\uff44=$short",
            "unicode-combining" to "pa\u0301ssword=$short", "unicode-zero-width" to "pass\u200Bword=$short", "unicode-escaped-name" to "\\u0070assword=$short",
            "unicode-fullwidth-colon" to "password\uff1a$short", "unicode-fullwidth-equals" to "password\uff1d$short", "unicode-nbsp-sep" to "password=\u00a0$short",
            "unicode-nbsp-before" to "password\u00a0=\u00a0$short", "unicode-rtl-mark" to "pass\u200Fword=$short", "unicode-greek-o" to "passw\u03bfrd=$short",
            "unicode-turkish-dotless" to "TOKEN\u0131=$short", "unicode-kelvin" to "to\u212Aen=$short", "unicode-token-cyrillic-o" to "t\u043eken=$short")) add(n, i, short, real = false)
        add("pct-encoded-name", "%70assword=$short", short, real = false)
        add("html-entity-name", "&#112;assword=$short", short, real = false)
        add("wg-combining-mark", key.take(20) + "\u0301" + key.drop(20), listOf(key.take(18), key.drop(20)), real = false)
        add("secret-after-bom", "\uFEFFpassword=$short", short, real = false)
        add("key-with-spaces-inside", "PrivateKey = " + key.take(20) + " " + key.drop(20), listOf(key.take(20), key.drop(20)), real = false)
        // 14. Common surrounding noise
        add("log-prefix-token", "2026-11-12 10:00:00 E/Unbraid: connect: IllegalArgumentException: token=$short", short)
        add("two-secrets-one-line", "password=$short token=${short}B", listOf(short, "${short}B"))
        add("secret-then-secret-after-newline", "password=$short\ntoken=${short}C", "${short}C")
        add("tab-separated-fields", "password\t$short", short)
        add("field-name-prefix-word", "mypassword=$short", short)
        add("field-name-suffix-word", "password_hint=$short", short, real = false)
        add("word-secret-in-prose", "the secret is $short ok", short, real = false)
        add("word-token-in-prose", "invalid token $short here", short)
        add("word-key-in-prose", "invalid key $short here", short)
        add("word-password-in-prose-quoted", "wrong password \"$short\"", short)
        add("word-account-in-prose", "account $short not found", short)
        add("word-login-in-prose", "login $short failed", short)
    }

    /** Runs [f] on its own thread with a time limit so a runaway pattern cannot hang the test; returns ms, -1 on timeout, -2 on a crash. */
    private fun timed(limitMs: Long, f: () -> Unit): Long {
        var err: String? = null
        val t = Thread { try { f() } catch (e: Throwable) { err = e.javaClass.simpleName } }; t.isDaemon = true
        val t0 = System.nanoTime(); t.start(); t.join(limitMs)
        return if (t.isAlive) -1 else if (err != null) -2 else (System.nanoTime() - t0) / 1_000_000
    }

    @Test fun survivorsStayBoundedAndNothingCrashes() {
        build()
        var survivors = 0; var real = 0; var crashes = 0
        for (c in cases) {
            val o = try { Redact.clean(c.input) } catch (e: Throwable) { crashes++; continue }
            if (c.witnesses.any { o.contains(it) }) { survivors++; if (c.real) real++ }
        }
        assertEquals(0, crashes)
        assertTrue("more made-up secrets get through: $survivors", survivors <= MAX_SURVIVORS)
        assertTrue("more real-shaped secrets get through: $real", real <= MAX_REAL_SURVIVORS)
    }

    /** Large and hostile inputs finish well inside the limit and never overflow the stack. */
    @Test fun hostileInputsFinish() {
        val lines = (0 until 20_000).map { i ->
            when (i % 5) {
                0 -> "2026-11-12 10:00:$i I/Unbraid: health: nl-$i failed ${i}x, 12 s"
                1 -> "E/Unbraid: connect: IllegalArgumentException: Invalid key in the config token=abc$i"
                2 -> "D/Unbraid: {\"private_key\":\"${Base64.getEncoder().encodeToString(ByteArray(32) { (it + i).toByte() })}\"}"
                3 -> "W/Unbraid: tailscale: dial tcp 192.0.2.${i % 255}:443: i/o timeout, password=\"two words $i\""
                else -> "I/Unbraid: ${"benign text ".repeat(8)}"
            }
        }
        val big = lines.joinToString("\n").let { s -> (s + "\n").repeat(5_000_000 / s.length + 1) }.take(5_000_000)
        val inputs = listOf(
            big, "password=\"" + "\\\"".repeat(500_000), "password=\"x\\".repeat(110_000).take(1_000_000), "token='" + "\\'".repeat(500_000),
            "password=\"".repeat(100_000), "A".repeat(1_000_000), "password=\"" + "a".repeat(2_000_000), "token='" + "a".repeat(2_000_000),
            "{\"token\":\"" + "a".repeat(2_000_000) + "\"}",
        )
        for ((n, input) in inputs.withIndex()) {
            val ms = timed(LIMIT_MS) { Redact.clean(input) }
            assertTrue("input $n: ${if (ms == -1L) "over the limit" else "crashed"}", ms >= 0)
        }
        assertTrue(timed(LIMIT_MS) { big.lineSequence().forEach { Redact.clean(it) } } >= 0)
    }

    private companion object {
        /** Survivors at the time of writing (68 of 477, 43 of them real-shaped): lower is better, higher fails. */
        const val MAX_SURVIVORS = 68
        const val MAX_REAL_SURVIVORS = 43
        const val LIMIT_MS = 60_000L
    }
}
