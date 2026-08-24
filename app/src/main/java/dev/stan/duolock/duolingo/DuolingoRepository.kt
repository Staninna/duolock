package dev.stan.duolock.duolingo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single owner of Duolingo API data: one HTTP client, one cached user
 * snapshot, one place that notices an expired login. The gate loop reads
 * [state] without ever suspending; refreshes run on whoever calls [refresh]
 * (the monitor's background refresher, or a UI pull-to-refresh).
 */
class DuolingoRepository private constructor(private val api: DuolingoApi = DuolingoApi()) {

    data class UserState(
        val user: UserResponse? = null,
        val fetchedAtMs: Long = 0L,
        val error: String? = null,
        val authExpired: Boolean = false,
    )

    private val _state = MutableStateFlow(UserState())
    val state: StateFlow<UserState> = _state

    private val refreshMutex = Mutex()

    /**
     * Fetch the user unless a fetch newer than [minIntervalMs] already exists
     * (successful or failed — a failing network is not retried every caller).
     * Returns the resulting state.
     */
    suspend fun refresh(jwt: String, userId: Long, minIntervalMs: Long = 0L): UserState {
        if (jwt.isBlank() || userId <= 0) return _state.value
        refreshMutex.withLock {
            val now = System.currentTimeMillis()
            val cur = _state.value
            if (now - cur.fetchedAtMs < minIntervalMs) return cur
            _state.value = try {
                UserState(user = api.fetchUser(jwt, userId), fetchedAtMs = now)
            } catch (e: DuolingoApiException) {
                cur.copy(
                    fetchedAtMs = now,
                    error = e.message,
                    authExpired = e.code == 401,
                )
            } catch (e: Exception) {
                cur.copy(fetchedAtMs = now, error = e.message ?: "network error")
            }
            return _state.value
        }
    }

    companion object {
        @Volatile private var instance: DuolingoRepository? = null
        fun get(): DuolingoRepository =
            instance ?: synchronized(this) {
                instance ?: DuolingoRepository().also { instance = it }
            }
    }
}
