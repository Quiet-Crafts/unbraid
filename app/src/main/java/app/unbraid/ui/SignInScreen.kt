package app.unbraid.ui

import app.unbraid.WORDMARK
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.remember
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.height
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.unbraid.data.Field
import app.unbraid.data.Provider
import app.unbraid.data.Providers

/** First run, or "Add provider" when [onClose] is set. Step 1 picks a provider or an import; step 2 is that provider's form. Never in screenshots. */
@Composable
fun SignInScreen(ui: Ui, onClose: (() -> Unit)? = null) {
    var step by rememberSaveable { mutableStateOf<String?>(null) }
    val provider = Providers.get(step.orEmpty())
    SecureWindow()
    BackHandler(provider != null) { provider?.let { ui.discardForm(it.id) }; step = null }
    if (provider == null) Choose(ui, onClose) { step = it.id } else Form(ui, provider, onClose, back = { ui.discardForm(provider.id); step = null })
}

private class Other(val title: String, val sub: String, val go: () -> Unit)

@Composable
private fun Choose(ui: Ui, onClose: (() -> Unit)?, pick: (Provider) -> Unit) {
    val importer = rememberImporter(ui, "") { onClose?.invoke() }
    val accounts by ui.accounts.collectAsStateWithLifecycle()
    val notice by ui.notice.collectAsStateWithLifecycle()
    val providers = Providers.all.filter { it.id !in accounts }
    Screen(
        // First run greets with the logo; adding one later is an ordinary page with Close top-leading.
        overline = if (onClose == null) "Sign in" else "Providers & servers", title = if (onClose == null) WORDMARK else "Add provider", sub = "Choose your VPN",
        navigation = onClose?.let { { IconTap(Icons.close, "Close", it) } },
    ) {
        // A sign-out that couldn't finish (e.g. a device slot left registered) lands here after the last provider goes.
        notice?.let { n -> item { Caption(n, Modifier.tap({ ui.notice.value = null }), color = Ink.bad) } }
        item { Gap(8.dp) }
        itemsIndexed(providers) { i, p -> GroupRow(i, providers.size, p.name, p.fields.joinToString(" · ") { it.label }, onClick = { pick(p) }, trailing = { Chevron() }) }
        item { SectionLabel("Other") }
        val other = listOfNotNull(
            Other("Import a config file", "WireGuard .conf or OpenVPN .ovpn") { importer.file() },
            Other("Shadowsocks link", "Paste an ss:// access key") { importer.shadowsocks() },
            Other("Tailscale only", "No VPN provider, just your tailnet") { ui.useTailscaleOnly() }.takeIf { onClose == null },
        )
        itemsIndexed(other) { i, o -> GroupRow(i, other.size, o.title, o.sub, onClick = o.go, trailing = { Chevron() }) }
        importer.error?.let { e -> item { Caption(e, color = Ink.bad) } }
    }
    importer.Dialogs()
}

@Composable
private fun Form(ui: Ui, provider: Provider, onClose: (() -> Unit)?, back: () -> Unit) {
    val accounts by ui.accounts.collectAsStateWithLifecycle()
    val signing by ui.signing.collectAsStateWithLifecycle()
    val busy = provider.id in signing
    val error = ui.formError[provider.id]
    val link = ui.formLink[provider.id]
    // A question the provider asked (a two-factor code, a captcha) is one more field, answered and sent with the rest.
    val asked = ui.challenge[provider.id]
    val fields = provider.fields + listOfNotNull(asked?.field)
    fun key(f: Field) = "${provider.id}:${f.key}"
    val context = androidx.compose.ui.platform.LocalContext.current
    var scanFor by rememberSaveable(provider.id) { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable(provider.id) { mutableStateOf(false) }
    val scan = rememberQrScan { uri -> provider.fields.firstOrNull { it.key == scanFor }?.let { ui.readQr(uri, key(it), provider.id) } }
    // The field's key, saved: the picker may outlive this activity (rotation), and its file must still land.
    var fileFor by rememberSaveable(provider.id) { mutableStateOf<String?>(null) }
    // Config files keep their line breaks only when loaded from a file; a pasted .conf in one line can't be parsed.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val f = provider.fields.firstOrNull { it.key == fileFor } ?: return@rememberLauncherForActivityResult
        fileFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        // In the view model, off the main thread: a slow document provider neither freezes the app nor loses the file on rotation.
        ui.loadFormFile(uri, key(f), provider.id)
    }
    val ready = fields.all { ui.form[key(it)].orEmpty().isNotBlank() }
    // The sign-in runs in the view model; when it lands, this form is done.
    val done = provider.id in accounts
    LaunchedEffect(done) { if (done) onClose?.invoke() }

    fun submit() {
        if (busy || !ready) return
        ui.signIn(provider, fields.associate { it.key to ui.form[key(it)].orEmpty() })
    }

    Box(Modifier.fillMaxSize()) {
        Screen(
            overline = "Sign in", title = provider.name, sub = "Enter your details",
            navigation = { IconTap(Icons.back, "Back", back) },
        ) {
            item {
                Column(Modifier.padding(start = Gutter, end = Gutter, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    fields.forEachIndexed { i, f ->
                        if (f == asked?.field) asked.image?.let { png ->
                            remember(png) { android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }?.let {
                                androidx.compose.foundation.Image(it, "Captcha", Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(96.dp).background(androidx.compose.ui.graphics.Color.White))
                            }
                        }
                        Text(f.label, style = Type.sub.copy(fontWeight = FontWeight.Medium), color = Ink.text, modifier = Modifier.padding(horizontal = 8.dp))
                        PillField(
                            ui.form[key(f)].orEmpty(), { ui.form[key(f)] = it; if (f != asked?.field) { ui.formError.remove(provider.id); ui.formLink.remove(provider.id); ui.challenge.remove(provider.id) } },
                            f.label, placeholder = f.hint.ifBlank { f.label }, secret = f.secret,
                            imeAction = if (i == fields.lastIndex) ImeAction.Done else ImeAction.Next, onDone = ::submit,
                        )
                        if (f.qr) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Or scan its QR code: take a photo, or pick a screenshot", style = Type.caption, color = Ink.dim, modifier = Modifier.weight(1f))
                            Secondary("Scan QR code", { scanFor = f.key; scanning = true })
                        }
                        if (f.file) Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val v = ui.form[key(f)].orEmpty()
                            Text(if ('\n' in v) "Config file loaded" else "Or load the downloaded file", style = Type.caption, color = Ink.dim, modifier = Modifier.weight(1f))
                            Secondary("Choose file", { fileFor = f.key; pickFile.launch(arrayOf("*/*")) })
                        }
                    }
                    // A question isn't a failure: it reads as an instruction, not in red.
                    error?.let { Text(it, style = Type.caption, color = if (asked != null) Ink.text else Ink.bad, modifier = Modifier.padding(horizontal = 8.dp)) }
                    link?.let { u -> Secondary("Open the ${provider.name} client area", { context.openUrl(u) }) }
                    Text(
                        "${provider.help} Stored encrypted on this phone.",
                        style = Type.caption, color = Ink.dim, modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Ink.ground).imePadding().navigationBarsPadding()
                .padding(start = Gutter, end = Gutter, top = 8.dp, bottom = 16.dp),
        ) { Primary("Continue", ::submit, enabled = ready, busy = busy) }
    }
    if (scanning) Prompt("Scan QR code", "Take a photo", { scanning = false; scan.photo() }, { scanning = false }) {
        Text("Photograph the QR code with your camera app, or pick a screenshot of it.", style = Type.sub)
        Secondary("Choose a picture", { scanning = false; scan.picture() })
    }
}
