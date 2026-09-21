package com.triplethreats.masteria.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.triplethreats.masteria.AppContainer
import com.triplethreats.masteria.ui.main.MainScreen
import com.triplethreats.masteria.ui.onboarding.DiagnosticScreen
import com.triplethreats.masteria.ui.onboarding.OnboardingScreen
import com.triplethreats.masteria.ui.profile.ProgressScreen
import com.triplethreats.masteria.ui.profile.SettingsScreen
import com.triplethreats.masteria.ui.quest.QuestScreen
import com.triplethreats.masteria.ui.welcome.AuthScreen
import com.triplethreats.masteria.ui.welcome.WelcomeScreen
import com.triplethreats.masteria.ui.theme.Masteria
import com.triplethreats.masteria.ui.theme.Springs

object Routes {
    const val WELCOME = "welcome"
    const val AUTH = "auth/{mode}"
    const val ONBOARDING = "onboarding"
    const val DIAGNOSTIC = "diagnostic"
    const val MAIN = "main"
    const val QUEST = "quest?topic={topic}&boss={boss}&pending={pending}"
    const val PROGRESS = "progress"
    const val SETTINGS = "settings"

    fun auth(signUp: Boolean) = "auth/${if (signUp) "signup" else "signin"}"
    fun quest(topicId: String? = null, boss: Boolean = false, pending: Boolean = false) =
        "quest?topic=${topicId ?: ""}&boss=$boss&pending=$pending"
}

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("No container") }

/** ViewModels get the app container through a tiny factory; no DI framework needed. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Leave the flow and make [route] the only thing on the stack. */
fun NavHostController.resetTo(route: String) = navigate(route) {
    popUpTo(0) { inclusive = true }
    launchSingleTop = true
}

// iOS push: the new screen slides over from the trailing edge while the old one drifts a
// third of the way back and dims. Critically damped, so nothing overshoots.
private val pushSpec = Springs.apple<IntOffset>(damping = 1f, response = 0.42f, visibilityThreshold = IntOffset(1, 1))

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isModal(): Boolean =
    targetState.destination.route?.startsWith("quest") == true || initialState.destination.route?.startsWith("quest") == true

@Composable
fun AppNav(container: AppContainer, start: String) {
    val nav = rememberNavController()
    val reduced = Masteria.reducedMotion
    CompositionLocalProvider(LocalContainer provides container) {
        NavHost(
            navController = nav,
            startDestination = start,
            modifier = Modifier
                .fillMaxSize()
                .background(Masteria.colors.background),
            enterTransition = {
                when {
                    reduced -> fadeIn(tween(200))
                    targetState.destination.route?.startsWith("quest") == true ->
                        slideInVertically(Springs.sheet(IntOffset(1, 1))) { it }
                    else -> slideInHorizontally(pushSpec) { it }
                }
            },
            exitTransition = {
                when {
                    reduced -> fadeOut(tween(200))
                    isModal() -> fadeOut(tween(250), targetAlpha = 0.6f)
                    else -> slideOutHorizontally(pushSpec) { -it / 3 } + fadeOut(tween(350), targetAlpha = 0.7f)
                }
            },
            popEnterTransition = {
                when {
                    reduced -> fadeIn(tween(200))
                    isModal() -> fadeIn(tween(250), initialAlpha = 0.6f)
                    else -> slideInHorizontally(pushSpec) { -it / 3 } + fadeIn(tween(350), initialAlpha = 0.7f)
                }
            },
            popExitTransition = {
                when {
                    reduced -> fadeOut(tween(200))
                    initialState.destination.route?.startsWith("quest") == true ->
                        slideOutVertically(Springs.sheet(IntOffset(1, 1))) { it } + scaleOut(targetScale = 0.98f)
                    else -> slideOutHorizontally(pushSpec) { it }
                }
            },
        ) {
            composable(Routes.WELCOME, enterTransition = { EnterTransition.None }, exitTransition = { fadeOut(tween(250)) }) {
                WelcomeScreen(
                    onStart = { nav.navigate(Routes.auth(signUp = true)) },
                    onSignIn = { nav.navigate(Routes.auth(signUp = false)) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.AUTH, arguments = listOf(navArgument("mode") { type = NavType.StringType })) { entry ->
                AuthScreen(
                    signUp = entry.arguments?.getString("mode") == "signup",
                    onBack = { nav.popBackStack() },
                    onDone = { user ->
                        nav.resetTo(
                            when {
                                !user.onboarded -> Routes.ONBOARDING
                                !user.diagnosed -> Routes.DIAGNOSTIC
                                else -> Routes.MAIN
                            }
                        )
                    },
                    onSwitch = { signUp ->
                        nav.navigate(Routes.auth(signUp)) { popUpTo(Routes.WELCOME) }
                    },
                )
            }
            composable(Routes.ONBOARDING, enterTransition = { fadeIn(tween(300)) }) {
                OnboardingScreen(onDone = { nav.resetTo(Routes.DIAGNOSTIC) })
            }
            composable(Routes.DIAGNOSTIC) {
                DiagnosticScreen(onDone = { nav.resetTo(Routes.MAIN) })
            }
            composable(Routes.MAIN, enterTransition = { fadeIn(tween(350)) }) {
                MainScreen(
                    onStartQuest = { topicId, boss -> nav.navigate(Routes.quest(topicId, boss)) },
                    onStartPendingQuest = { nav.navigate(Routes.quest(pending = true)) },
                    onOpenProgress = { nav.navigate(Routes.PROGRESS) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onNeedsDiagnostic = { nav.resetTo(Routes.DIAGNOSTIC) },
                    onSignedOut = { nav.resetTo(Routes.WELCOME) },
                )
            }
            composable(
                Routes.QUEST,
                arguments = listOf(
                    navArgument("topic") { type = NavType.StringType; defaultValue = "" },
                    navArgument("boss") { type = NavType.BoolType; defaultValue = false },
                    navArgument("pending") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                val args = entry.arguments
                QuestScreen(
                    topicId = args?.getString("topic")?.takeIf { it.isNotBlank() },
                    boss = args?.getBoolean("boss") ?: false,
                    pending = args?.getBoolean("pending") ?: false,
                    onClose = { nav.popBackStack() },
                    onNextQuest = { topicId, boss ->
                        nav.navigate(Routes.quest(topicId, boss)) {
                            popUpTo(Routes.MAIN)
                        }
                    },
                )
            }
            composable(Routes.PROGRESS) {
                ProgressScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onSignedOut = { nav.resetTo(Routes.WELCOME) },
                    // Guest → real account: signing up while the guest token is active keeps all progress.
                    onCreateAccount = { nav.navigate(Routes.auth(signUp = true)) },
                )
            }
        }
    }
}
