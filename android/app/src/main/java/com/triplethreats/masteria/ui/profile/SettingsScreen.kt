package com.triplethreats.masteria.ui.profile

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.BuildConfig
import com.triplethreats.masteria.data.AiStatusDto
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.ActivitySpinner
import com.triplethreats.masteria.ui.components.Dot
import com.triplethreats.masteria.ui.components.FieldRow
import com.triplethreats.masteria.ui.components.GroupedList
import com.triplethreats.masteria.ui.components.LargeTitleScreen
import com.triplethreats.masteria.ui.components.ListRow
import com.triplethreats.masteria.ui.components.SectionHeader
import com.triplethreats.masteria.ui.theme.Masteria
import kotlinx.coroutines.launch

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val session = container.session
    var address by mutableStateOf(container.session.baseUrl)
    var testing by mutableStateOf(false)
        private set
    var reachable by mutableStateOf<Boolean?>(null)
        private set
    var ai by mutableStateOf<AiStatusDto?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun test() {
        testing = true; reachable = null
        viewModelScope.launch {
            val url = normalised()
            val ok = container.api.ping(url)
            reachable = ok
            if (ok) {
                val changed = url != container.session.baseUrl
                container.session.setBaseUrl(url)
                if (changed) container.serverChanged()
                address = url
                ai = runCatching { container.api.aiStatus() }.getOrNull()
            }
            testing = false
        }
    }

    private fun normalised(): String {
        val a = address.trim().trimEnd('/')
        return when {
            a.startsWith("http://") || a.startsWith("https://") -> a
            // Render deployments are HTTPS only; bare LAN addresses are local dev servers.
            a.contains(".onrender.com") -> "https://$a"
            else -> "http://$a"
        }
    }

    fun signOut(done: () -> Unit) {
        viewModelScope.launch {
            container.signOut(); done()
        }
    }

    fun delete(done: () -> Unit) {
        busy = true; error = null
        viewModelScope.launch {
            try {
                container.api.deleteAccount()
                container.firebase.deleteCurrentUser()
                container.signOut()
                done()
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onSignedOut: () -> Unit, onCreateAccount: () -> Unit = {}) {
    val vm = appViewModel { SettingsViewModel(it) }
    val c = Masteria.colors
    val user by vm.session.user.collectAsState()
    val signedIn = vm.session.token != null
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.test() }

    LargeTitleScreen(title = "Settings", onBack = onBack, bottomInset = 0.dp) {
        item(key = "serverHeader") { SectionHeader("Server") }
        item(key = "server") {
            GroupedList {
                FieldRow(
                    "Address", vm.address, { vm.address = it },
                    placeholder = "http://192.168.1.10:8080",
                    keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done,
                    onImeAction = { vm.test() },
                )
                ListRow(
                    title = when (vm.reachable) {
                        true -> "Connected"
                        false -> "Can't reach the server"
                        null -> "Checking…"
                    },
                    subtitle = when (vm.reachable) {
                        false -> "Use your Render URL (https://<name>.onrender.com; the first call can take up to a minute while it wakes), or a laptop's LAN address. The emulator uses 10.0.2.2."
                        else -> null
                    },
                    icon = Icons.Rounded.Cloud,
                    iconTint = when (vm.reachable) { true -> c.success; false -> c.danger; null -> c.secondaryLabel },
                    chevron = false,
                    showDivider = false,
                    trailing = {
                        if (vm.testing) ActivitySpinner(size = 18.dp)
                        else TextButton(onClick = { vm.test() }) { Text("Test", color = c.brand, style = Masteria.type.body) }
                    },
                )
            }
        }

        vm.ai?.let { ai ->
            item(key = "aiHeader") { SectionHeader("AI") }
            item(key = "ai") {
                GroupedList {
                    ListRow(
                        "NVIDIA NIM",
                        subtitle = "${ai.chatModel}\nVision: ${ai.visionModel}" + (ai.lastLatencyMs?.let { "\nLast reply in ${it} ms" } ?: ""),
                        icon = Icons.Rounded.AutoAwesome, iconTint = c.brand, chevron = false, showDivider = false,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Dot(if (ai.online) c.success else c.warning)
                                Spacer(Modifier.width(6.dp))
                                Text(if (ai.online) "Online" else "Fallback", style = Masteria.type.footnote, color = c.secondaryLabel)
                            }
                        },
                    )
                }
                Text(
                    "The API key lives only on the server. If NIM is slow or down, quests keep working from the vetted question bank.",
                    style = Masteria.type.footnote, color = c.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
            }
        }

        if (signedIn) {
            item(key = "accountHeader") { SectionHeader("Account") }
            item(key = "account") {
                GroupedList {
                    ListRow(
                        user?.name ?: "Signed in", subtitle = if (user?.isGuest == true) "Guest" else user?.email,
                        icon = Icons.Rounded.Shield, iconTint = c.info, chevron = false,
                    )
                    if (user?.isGuest == true) {
                        ListRow(
                            "Create an account to save progress",
                            subtitle = "Keeps your XP, badges and streak, and lets you sign in on another phone.",
                            icon = Icons.Rounded.PersonAdd, iconTint = c.brand,
                            onClick = onCreateAccount,
                        )
                    }
                    ListRow(
                        "Sign out", icon = Icons.AutoMirrored.Rounded.Logout, iconTint = c.secondaryLabel,
                        onClick = { vm.signOut(onSignedOut) }, chevron = false,
                    )
                    ListRow(
                        "Delete account and data", icon = Icons.Rounded.DeleteForever, iconTint = c.danger,
                        titleColor = c.danger, onClick = { confirmDelete = true }, chevron = false, showDivider = false,
                    )
                }
                vm.error?.let { Text(it, style = Masteria.type.footnote, color = c.danger, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
            }
        }

        item(key = "aboutHeader") { SectionHeader("About") }
        item(key = "about") {
            GroupedList {
                ListRow(
                    "Masteria ${BuildConfig.VERSION_NAME}",
                    subtitle = "Team Triple Threats · iQOO Hackathon 2026\nThe learner model decides; the AI writes.",
                    icon = Icons.Rounded.Info, iconTint = c.brand, chevron = false, showDivider = false,
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?", style = Masteria.type.headline) },
            text = { Text("Your progress, XP, badges and history are removed from the server. This can't be undone.", style = Masteria.type.subhead) },
            confirmButton = {
                if (vm.busy) ActivitySpinner() else
                    TextButton(onClick = { vm.delete { confirmDelete = false; onSignedOut() } }) {
                        Text("Delete", color = c.danger, style = Masteria.type.headline)
                    }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.brand) } },
            containerColor = c.card,
            shape = RoundedCornerShape(22.dp),
        )
    }
}
