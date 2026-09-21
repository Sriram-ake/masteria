package com.triplethreats.masteria

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.triplethreats.masteria.data.ApiException
import com.triplethreats.masteria.ui.AppNav
import com.triplethreats.masteria.ui.Routes
import com.triplethreats.masteria.ui.components.GlassEnvironment
import com.triplethreats.masteria.ui.components.LocalGlass
import com.triplethreats.masteria.ui.theme.MasteriaTheme
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var start by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        splash.setKeepOnScreenCondition { start == null }

        val container = (application as MasteriaApp).container
        lifecycleScope.launch { start = resolveStart(container) }

        setContent {
            MasteriaTheme {
                val haze = rememberHazeState()
                val glass = remember(haze) { GlassEnvironment(haze) }
                CompositionLocalProvider(LocalGlass provides glass) {
                    start?.let { AppNav(container, it) }
                }
            }
        }
    }

    /** Where a cold start lands: signed out → welcome; half-onboarded → resume; else Today. */
    private suspend fun resolveStart(container: AppContainer): String {
        val session = container.session
        session.load()
        if (session.token == null) return Routes.WELCOME
        return try {
            val me = container.api.me()
            session.setUser(me)
            when {
                !me.onboarded -> Routes.ONBOARDING
                !me.diagnosed -> Routes.DIAGNOSTIC
                else -> Routes.MAIN
            }
        } catch (e: ApiException) {
            if (e.code == 401 || e.code == 404) {
                container.signOut(); Routes.WELCOME
            } else {
                // Server unreachable: open the app anyway on cached data; screens show retry.
                Routes.MAIN
            }
        }
    }
}
