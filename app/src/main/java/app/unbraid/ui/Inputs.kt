package app.unbraid.ui

import androidx.core.net.toUri
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import app.unbraid.APP
import app.unbraid.vpn.TsLogin
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.unit.sp

/** Android 17 blocks Unbraid's own LAN traffic without it, so Tailscale would only reach home through a slow relay. */
const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun Context.needsLocalNetwork(setup: app.unbraid.data.Setup, lockdownLan: Boolean = false) = (setup.tailscale || setup.home.wg != null || lockdownLan || setup.routes.any { it.onLan() }) &&
    android.os.Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED

private val LAN_IP = Regex("""^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.|169\.254\.)""")

/** An own server on the local network (a router or NAS): Unbraid's sockets to it need the local-network permission too. */
private fun app.unbraid.data.Route.onLan() = (listOfNotNull(wg?.endpoint?.substringBeforeLast(':'), ss?.server) +
    ovpn?.config.orEmpty().lineSequence().map { it.trim() }.filter { it.startsWith("remote ") }.map { it.split(Regex("\\s+"))[1] }).any(LAN_IP::containsMatchIn)

/** Android's strict Private DNS host, if set: Android then does its own DNS and skips Unbraid's per-app resolvers. */
fun Context.privateDnsHost(): String? = getSystemService(android.net.ConnectivityManager::class.java).run { getLinkProperties(activeNetwork)?.privateDnsServerName }

fun Context.openUrl(url: String) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) } }

/**
 * Opens a Tailscale sign-in link from the control server ([TsLogin]): its own host opens straight away, another web
 * address only after the user has seen it, and anything that isn't a web address never.
 */
@Composable
fun rememberTsLogin(controlUrl: String?): (String) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<TsLogin.Result?>(null) }
    var refused by remember { mutableStateOf(false) }
    pending?.let { r ->
        Prompt("Open this sign-in link?", "Open", { pending = null; r.url?.let(context::openUrl) }, { pending = null }) {
            Text("Your control server sent a sign-in link to ${r.host}, not to its own address. Open it only if you trust that site.")
        }
    }
    if (refused) Prompt("Sign-in link not opened", "OK", { refused = false }, { refused = false }) {
        Text("Your control server sent a sign-in link that isn't a secure web address, so $APP won't open it.")
    }
    return { url ->
        val r = TsLogin.check(url, controlUrl)
        when (r.verdict) {
            TsLogin.Verdict.OPEN -> r.url?.let(context::openUrl)
            TsLogin.Verdict.ASK -> pending = r
            TsLogin.Verdict.REFUSE -> refused = true
        }
    }
}

@Composable
fun Chevron(value: String? = null) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    value?.let { Text(it, style = Type.small, color = Ink.dim) }
    Icon(Icons.chevron, null, Modifier.size(16.dp), tint = Ink.faint)
}

/** Pill text field. Secret fields are monospace with a show/hide eye. */
@Composable
fun PillField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = label, secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Done, onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val pill = RoundedCornerShape(28.dp)
    val style = if (secret) Type.body.copy(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp) else Type.body
    Row(
        modifier.fillMaxWidth().height(56.dp).clip(pill).background(Ink.surfaceHigh)
            .border(2.dp, if (focused) Ink.accent else Color.Transparent, pill).padding(start = 20.dp, end = if (secret) 4.dp else 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = style, color = Ink.dim, maxLines = 1)
            BasicTextField(
                value, onChange, singleLine = true, textStyle = style.copy(color = Ink.text), cursorBrush = SolidColor(Ink.accent),
                visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false, imeAction = imeAction),
                keyboardActions = KeyboardActions(onDone = { onDone() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
            )
        }
        if (secret) IconTap(if (visible) Icons.eyeOff else Icons.eye, if (visible) "Hide $label" else "Show $label", { visible = !visible }, tint = Ink.dim)
    }
}

@Composable
fun inkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Ink.text, unfocusedTextColor = Ink.text, errorTextColor = Ink.text, cursorColor = Ink.accent, errorCursorColor = Ink.bad,
    focusedBorderColor = Ink.accent, unfocusedBorderColor = Ink.line, errorBorderColor = Ink.bad,
    focusedLabelColor = Ink.accent, unfocusedLabelColor = Ink.dim, errorLabelColor = Ink.bad,
    focusedPlaceholderColor = Ink.faint, unfocusedPlaceholderColor = Ink.faint,
    focusedSupportingTextColor = Ink.dim, unfocusedSupportingTextColor = Ink.dim, errorSupportingTextColor = Ink.bad,
)

/** Dialog text field. Secret fields are masked, with a show/hide eye. */
@Composable
fun DialogField(value: String, onChange: (String) -> Unit, label: String, placeholder: String = "", secret: Boolean = false, singleLine: Boolean = true) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), singleLine = singleLine, label = { Text(label, style = Type.caption) },
        textStyle = Type.body.copy(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp),
        placeholder = { Text(placeholder, style = Type.body.copy(fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 13.sp)) },
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {
            { IconTap(if (visible) Icons.eyeOff else Icons.eye, if (visible) "Hide $label" else "Show $label", { visible = !visible }, size = 40.dp, tint = Ink.dim) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
        colors = inkFieldColors(),
    )
}

private var secureCount = 0

/** Keeps the window out of screenshots and the recents thumbnail while it is composed. Nested uses count. */
@Composable
fun SecureWindow() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        secureCount++
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (--secureCount == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** A confirm dialog. With [secure] its own window is also kept out of screenshots (a dialog is a window of its own). */
@Composable
fun Prompt(
    title: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    enabled: Boolean = true, danger: Boolean = false, secure: Boolean = false, content: @Composable ColumnScope.() -> Unit,
) = AlertDialog(
    onDismissRequest = onDismiss, containerColor = Ink.surface, titleContentColor = Ink.text, textContentColor = Ink.dim,
    properties = DialogProperties(securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
    title = { Text(title, style = Type.title) },
    text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content) },
    confirmButton = { Secondary(confirm, onConfirm, color = if (!enabled) Ink.faint else if (danger) Ink.bad else Ink.accent, enabled = enabled) },
    dismissButton = { Secondary("Cancel", onDismiss, color = Ink.dim) },
)

@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Ink.dim) =
    Text(text, style = Type.caption, color = color, modifier = modifier.padding(start = 24.dp, end = Gutter, top = 8.dp))
