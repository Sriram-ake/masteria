package com.triplethreats.masteria

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.triplethreats.masteria.data.Api
import com.triplethreats.masteria.data.FirebaseAuthClient
import com.triplethreats.masteria.data.QuestSessionDto
import com.triplethreats.masteria.data.RealtimeClient
import com.triplethreats.masteria.data.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MasteriaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual DI: one of each, created with the app. */
class AppContainer(app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val session = Session(app)
    val api = Api(baseUrl = { session.baseUrl }, token = { session.token })
    val firebase = FirebaseAuthClient(app)

    /** Persistent WebSocket for low-latency calls and "changed" pushes; HTTP is the fallback. */
    val realtime = RealtimeClient(
        baseHttp = api.http,
        baseUrl = { session.baseUrl },
        token = { session.token },
        onChanged = { scope.launch { invalidate() } },
        onUnauthorized = { /* token rejected: the next HTTP call returns 401 and the screens send the user to sign in */ },
    ).also { api.realtime = it }

    /** Bumped whenever XP, mastery or the map may have changed, so visible screens refetch. */
    private val _dataVersion = MutableStateFlow(0)
    val dataVersion: StateFlow<Int> = _dataVersion.asStateFlow()
    fun invalidate() {
        _dataVersion.value++
    }

    /** A quest that was already started elsewhere (Scan-to-Quest) and is handed to the quest screen. */
    var pendingQuest: QuestSessionDto? = null

    @Volatile private var foreground = false
    private var backgroundClose: Job? = null

    init {
        // Socket follows sign-in state while the app is in the foreground.
        scope.launch {
            session.tokenFlow.collect { token ->
                if (token == null) realtime.disconnect() else if (foreground) realtime.connect()
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                foreground = true
                backgroundClose?.cancel()
                if (session.token != null) realtime.connect()
            }

            override fun onStop(owner: LifecycleOwner) {
                foreground = false
                // Keep it briefly (camera, share sheet, quick app switch), then free the radio.
                backgroundClose = scope.launch {
                    delay(30_000)
                    if (!foreground) realtime.disconnect()
                }
            }
        })
    }

    /** Server address changed in Settings: reconnect the socket to the new server. */
    fun serverChanged() {
        realtime.disconnect()
        if (foreground && session.token != null) realtime.connect()
    }

    /** Signs out of the backend session, Firebase and the socket. */
    suspend fun signOut() {
        realtime.disconnect()
        firebase.signOut()
        session.signOut()
    }
}
