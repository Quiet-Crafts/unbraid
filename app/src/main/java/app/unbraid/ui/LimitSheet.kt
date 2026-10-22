package app.unbraid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Asks before a route change takes a provider over its connection limit ([Ui.limitAsk]); nothing changes until then. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LimitSheet(ui: Ui) {
    val ask by ui.limitAsk.collectAsStateWithLifecycle()
    val a = ask ?: return
    val done = { ui.limitAsk.value = null }
    ModalBottomSheet(done, containerColor = Ink.surface, contentColor = Ink.text) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Over your plan's limit", style = Type.title)
            Text(a.text, style = Type.sub, color = Ink.text)
            a.share?.let { (server, use) ->
                Primary("Use $server for both routes", { use(); done() })
                Text("Both routes move to this server and share one connection", style = Type.caption, color = Ink.dim)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Secondary("Cancel", done, Modifier.weight(1f), color = Ink.dim)
                Secondary("Change anyway", { a.apply(); done() }, Modifier.weight(1f))
            }
        }
    }
}
