package com.triplethreats.masteria.ui.welcome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.data.AuthResponse
import com.triplethreats.masteria.data.UserDto
import com.triplethreats.masteria.ui.appViewModel
import com.triplethreats.masteria.ui.components.BackButton
import com.triplethreats.masteria.ui.components.ButtonStyle
import com.triplethreats.masteria.ui.components.FieldRow
import com.triplethreats.masteria.ui.components.GroupedList
import com.triplethreats.masteria.ui.components.MButton
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.rememberHaptics
import kotlinx.coroutines.launch

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    var name by mutableStateOf("")
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var loading by mutableStateOf(false)
        private set
    var guestLoading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Inline validation, shown only once the user has typed something. */
    fun emailProblem(): String? =
        if (email.isNotBlank() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(email.trim())) "Enter a valid email address." else null

    fun passwordProblem(signUp: Boolean): String? =
        if (signUp && password.isNotEmpty() && password.length < 6) "Use at least 6 characters." else null

    fun canSubmit(signUp: Boolean) =
        email.isNotBlank() && password.isNotBlank() && emailProblem() == null && passwordProblem(signUp) == null &&
            (!signUp || name.isNotBlank())

    var info by mutableStateOf<String?>(null)
        private set
    var googleLoading by mutableStateOf(false)
        private set

    /** Firebase handles accounts when it's configured; otherwise the backend's own email/password auth. */
    val usesFirebase get() = container.firebase.available
    val googleAvailable get() = container.firebase.googleAvailable

    fun submit(signUp: Boolean, onDone: (UserDto) -> Unit) = run(onDone, Mode.Email) {
        if (usesFirebase) {
            val idToken = if (signUp) container.firebase.signUp(name.trim(), email.trim(), password)
            else container.firebase.signIn(email.trim(), password)
            exchange(idToken, name.trim().takeIf { signUp && it.isNotEmpty() })
        } else {
            if (signUp) container.api.register(name.trim(), email.trim(), password)
            else container.api.login(email.trim(), password)
        }
    }

    fun google(activityContext: Context, onDone: (UserDto) -> Unit) = run(onDone, Mode.Google) {
        val idToken = container.firebase.signInWithGoogle(activityContext) ?: return@run null
        exchange(idToken, null)
    }

    fun guest(onDone: (UserDto) -> Unit) = run(onDone, Mode.Guest) {
        container.api.guest(name.trim().ifBlank { "Explorer" })
    }

    fun resetPassword() {
        if (email.isBlank() || emailProblem() != null) {
            error = "Enter your email above first, then tap \"Forgot password?\"."
            return
        }
        error = null; info = null
        viewModelScope.launch {
            try {
                container.firebase.sendPasswordReset(email.trim())
                info = "Check your inbox for a link to reset your password."
            } catch (e: ApiException) {
                error = e.message
            }
        }
    }

    /**
     * Firebase ID token → Masteria session. If a guest is signed in, their token rides along (Api adds it),
     * so the backend turns that guest into this account and keeps all progress.
     */
    private suspend fun exchange(idToken: String, displayName: String?): AuthResponse =
        try {
            container.api.firebaseAuth(idToken, displayName)
        } catch (e: ApiException) {
            container.firebase.signOut() // don't leave a Firebase session the backend refused
            throw e
        }

    private enum class Mode { Email, Google, Guest }

    private fun run(onDone: (UserDto) -> Unit, mode: Mode, call: suspend () -> AuthResponse?) {
        if (loading || guestLoading || googleLoading) return
        error = null; info = null
        when (mode) {
            Mode.Email -> loading = true
            Mode.Google -> googleLoading = true
            Mode.Guest -> guestLoading = true
        }
        viewModelScope.launch {
            try {
                val auth = call() ?: return@launch
                container.session.signIn(auth)
                onDone(auth.user)
            } catch (e: ApiException) {
                error = e.message
            } finally {
                loading = false; guestLoading = false; googleLoading = false
            }
        }
    }
}

@Composable
fun AuthScreen(
    signUp: Boolean,
    onBack: () -> Unit,
    onDone: (UserDto) -> Unit,
    onSwitch: (Boolean) -> Unit,
) {
    val vm = appViewModel(key = "auth") { AuthViewModel(it) }
    val c = Masteria.colors
    val focus = LocalFocusManager.current
    val haptics = rememberHaptics()
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(c.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.padding(horizontal = 8.dp).height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(8.dp))
                Text(if (signUp) "Create your hero" else "Welcome back", style = Masteria.type.largeTitle, color = c.label)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (signUp) "Your progress, streak and badges are saved to your account."
                    else "Sign in to pick up your quests where you left them.",
                    style = Masteria.type.body, color = c.secondaryLabel,
                )
            }
            Spacer(Modifier.height(28.dp))
            GroupedList {
                if (signUp) {
                    FieldRow("Name", vm.name, { vm.name = it }, placeholder = "Asha")
                }
                FieldRow(
                    "Email", vm.email, { vm.email = it },
                    placeholder = "you@example.com", keyboardType = KeyboardType.Email,
                )
                FieldRow(
                    "Password", vm.password, { vm.password = it },
                    placeholder = if (signUp) "6+ characters" else "Required",
                    password = true, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done,
                    showDivider = false,
                    onImeAction = {
                        focus.clearFocus()
                        if (vm.canSubmit(signUp)) vm.submit(signUp, onDone)
                    },
                )
            }
            // Inline validation, right under the fields it concerns.
            val problem = vm.emailProblem() ?: vm.passwordProblem(signUp)
            AnimatedVisibility(problem != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    problem.orEmpty(), style = Masteria.type.footnote, color = c.warning,
                    modifier = Modifier.padding(start = 36.dp, top = 8.dp, end = 20.dp),
                )
            }
            if (!signUp && vm.usesFirebase) {
                TextButton(onClick = { focus.clearFocus(); vm.resetPassword() }, modifier = Modifier.padding(start = 20.dp)) {
                    Text("Forgot password?", style = Masteria.type.footnote, color = c.brand)
                }
            }
            AnimatedVisibility(vm.info != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Text(
                    vm.info.orEmpty(), style = Masteria.type.footnote, color = c.success,
                    modifier = Modifier.padding(start = 36.dp, top = 8.dp, end = 20.dp),
                )
            }
            AnimatedVisibility(vm.error != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Row(
                    Modifier.padding(start = 32.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = c.danger, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(vm.error.orEmpty(), style = Masteria.type.footnote, color = c.danger)
                }
            }

            Spacer(Modifier.height(24.dp))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MButton(
                    if (signUp) "Create account" else "Sign in",
                    onClick = {
                        focus.clearFocus()
                        vm.submit(signUp) { haptics.success(); onDone(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = vm.canSubmit(signUp),
                    loading = vm.loading,
                )
                if (vm.googleAvailable) {
                    MButton(
                        "Continue with Google",
                        onClick = { focus.clearFocus(); vm.google(context) { haptics.success(); onDone(it) } },
                        modifier = Modifier.fillMaxWidth(),
                        style = ButtonStyle.Tinted,
                        icon = Icons.Rounded.AccountCircle,
                        loading = vm.googleLoading,
                    )
                }
                MButton(
                    "Continue as guest",
                    onClick = { focus.clearFocus(); vm.guest { haptics.success(); onDone(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Tinted,
                    icon = Icons.Rounded.PersonOutline,
                    loading = vm.guestLoading,
                )
                MButton(
                    if (signUp) "I already have an account" else "New here? Create an account",
                    onClick = { onSwitch(!signUp) },
                    modifier = Modifier.fillMaxWidth(),
                    style = ButtonStyle.Plain,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
