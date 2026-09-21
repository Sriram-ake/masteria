package com.triplethreats.masteria.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.triplethreats.masteria.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

private val Context.dataStore by preferencesDataStore(name = "masteria")

/**
 * Signed-in state and small caches, persisted in DataStore: the Masteria JWT, the server address,
 * the current user, and the last Home and Map (so the app opens instantly and works offline).
 */
class Session(context: Context) {
    private val store = context.applicationContext.dataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val token = stringPreferencesKey("token")
        val baseUrl = stringPreferencesKey("baseUrl")
        val user = stringPreferencesKey("user")
        val home = stringPreferencesKey("cache.home")
        val map = stringPreferencesKey("cache.map")
    }

    private val _token = MutableStateFlow<String?>(null)
    /** Emits on sign-in / sign-out; the realtime connection follows it. */
    val tokenFlow: StateFlow<String?> = _token.asStateFlow()
    val token: String? get() = _token.value

    @Volatile var baseUrl: String = BuildConfig.BACKEND_URL.trimEnd('/')
        private set

    private val _user = MutableStateFlow<UserDto?>(null)
    val user: StateFlow<UserDto?> = _user.asStateFlow()

    suspend fun load() {
        val p = store.data.first()
        p[Keys.baseUrl]?.takeIf { it.isNotBlank() }?.let { baseUrl = it }
        _user.value = p[Keys.user]?.let { decode(UserDto.serializer(), it) }
        _token.value = p[Keys.token]?.takeIf { it.isNotBlank() }
    }

    suspend fun signIn(auth: AuthResponse) {
        // Another account's cached screens must never show for this one.
        store.edit {
            it[Keys.token] = auth.token
            it[Keys.user] = AppJson.encodeToString(UserDto.serializer(), auth.user)
            it.remove(Keys.home); it.remove(Keys.map)
        }
        _user.value = auth.user
        _token.value = auth.token
    }

    suspend fun signOut() {
        store.edit {
            it.remove(Keys.token); it.remove(Keys.user); it.remove(Keys.home); it.remove(Keys.map)
        }
        _token.value = null
        _user.value = null
    }

    /** Not suspending so it can be passed as a function reference; persisting happens in the background. */
    fun setUser(user: UserDto) {
        _user.value = user
        scope.launch { store.edit { it[Keys.user] = AppJson.encodeToString(UserDto.serializer(), user) } }
    }

    suspend fun setBaseUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        baseUrl = clean
        store.edit { it[Keys.baseUrl] = clean }
    }

    suspend fun cacheHome(home: HomeDto) = put(Keys.home, HomeDto.serializer(), home)
    suspend fun cachedHome(): HomeDto? = get(Keys.home, HomeDto.serializer())
    suspend fun cacheMap(map: MapDto) = put(Keys.map, MapDto.serializer(), map)
    suspend fun cachedMap(): MapDto? = get(Keys.map, MapDto.serializer())

    private suspend fun <T> put(key: Preferences.Key<String>, serializer: KSerializer<T>, value: T) {
        if (token == null) return
        store.edit { it[key] = AppJson.encodeToString(serializer, value) }
    }

    private suspend fun <T> get(key: Preferences.Key<String>, serializer: KSerializer<T>): T? =
        store.data.first()[key]?.let { decode(serializer, it) }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        runCatching { AppJson.decodeFromString(serializer, text) }.getOrNull()
}
