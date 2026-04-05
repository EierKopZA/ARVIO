package com.arflix.tv.data.repository

import android.util.Log
import com.arflix.tv.util.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages a Supabase Realtime WebSocket connection to receive instant
 * notifications when `account_sync_state` or `watch_history` changes on any device.
 *
 * Two channels are joined on the same socket:
 *
 * 1. `realtime:account_sync` \u2014 listens for UPDATEs on `account_sync_state`. On
 *    change, the manager triggers [CloudSyncRepository.pullFromCloud] to reapply the
 *    full JSON snapshot (addons, profiles, catalogs, IPTV config, preferences).
 *
 * 2. `realtime:watch_history` \u2014 listens for INSERTs/UPDATEs on `watch_history` so
 *    the Home screen's Continue Watching row can refresh on other devices within
 *    seconds of a progress update, instead of waiting for the user to reopen Home.
 *    Because watch_history is a high-write table during playback (~every 10s), the
 *    manager debounces events into [watchHistoryEvents] and the subscriber (HomeViewModel)
 *    is expected to collect the flow and call refreshContinueWatchingOnly(force = true).
 *    We do NOT trigger a full pullFromCloud on these events \u2014 that's wasteful for
 *    what is effectively a single-row position update. Fixes issue #91.
 *
 * A periodic fallback sync runs every 90 seconds (lowered from 5 minutes) in case the
 * WebSocket disconnects or misses an event on an unstable connection.
 */
@Singleton
class RealtimeSyncManager @Inject constructor(
    private val cloudSyncRepository: CloudSyncRepository,
    private val authRepository: AuthRepository
) {
    companion object {
        private const val TAG = "RealtimeSync"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        // Periodic fallback: was 5 minutes. Lowered to 90s so users on flaky connections
        // still get fresh playback resume positions within a reasonable time even if
        // the WebSocket silently misses an event (#91).
        private const val PERIODIC_SYNC_INTERVAL_MS = 90_000L
        private const val DEBOUNCE_MS = 2_000L // Debounce full snapshot pulls to avoid rapid-fire
        // Watch-history events fire every ~10s during playback. Coalesce bursts so we
        // don't hammer Home's Continue Watching refresh on every tick while a user is
        // actively watching something on the other device.
        private const val WATCH_HISTORY_DEBOUNCE_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private val msgRef = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private var pendingPullJob: Job? = null
    private var pendingWatchHistoryEmitJob: Job? = null

    // Track our own pushes to avoid pulling back what we just pushed
    @Volatile
    private var lastPushTimestamp = 0L

    // Track our own watch_history writes (they happen every ~10s during local
    // playback) so device A doesn't pointlessly refresh its own Continue Watching
    // row in response to its own updates.
    @Volatile
    private var lastLocalWatchHistoryWriteTimestamp = 0L

    // User JWT for authenticated Realtime subscriptions
    @Volatile
    private var currentAccessToken: String? = null

    // Event stream for watch_history realtime notifications. HomeViewModel collects
    // this and triggers refreshContinueWatchingOnly(force = true) on each emission.
    private val _watchHistoryEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val watchHistoryEvents: SharedFlow<Unit> = _watchHistoryEvents.asSharedFlow()

    fun markPush() {
        lastPushTimestamp = System.currentTimeMillis()
    }

    /**
     * Called by WatchHistoryRepository.saveProgress so incoming watch_history realtime
     * events from our own write can be ignored (device A shouldn't refresh its own CW
     * row when it was device A that just wrote the update).
     */
    fun markLocalWatchHistoryWrite() {
        lastLocalWatchHistoryWriteTimestamp = System.currentTimeMillis()
    }

    /**
     * Start listening for realtime changes. Call once after login.
     */
    fun start() {
        if (isRunning.getAndSet(true)) return
        Log.i(TAG, "Starting realtime sync")
        connectWebSocket()
        startPeriodicSync()
    }

    /**
     * Stop listening. Call on logout or app termination.
     */
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping realtime sync")
        webSocket?.close(1000, "App stopping")
        webSocket = null
        heartbeatJob?.cancel()
        periodicSyncJob?.cancel()
        reconnectJob?.cancel()
        pendingPullJob?.cancel()
        pendingWatchHistoryEmitJob?.cancel()
    }

    // ── WebSocket Connection ────────────────────────────────────────

    private fun connectWebSocket() {
        if (!isRunning.get()) return

        val userId = authRepository.getCurrentUserId()
        if (userId.isNullOrBlank()) {
            Log.w(TAG, "Not logged in, skipping WebSocket connection")
            scheduleReconnect()
            return
        }

        // Fetch user access token for authenticated Realtime subscriptions
        // Without the JWT, Supabase RLS blocks the postgres_changes subscription
        scope.launch {
            val accessToken = authRepository.getAccessToken()
            if (accessToken.isNullOrBlank()) {
                Log.w(TAG, "No access token, skipping WebSocket connection")
                scheduleReconnect()
                return@launch
            }
            connectWebSocketWithToken(userId, accessToken)
        }
    }

    private fun connectWebSocketWithToken(userId: String, accessToken: String) {
        if (!isRunning.get()) return

        val supabaseUrl = Constants.SUPABASE_URL
            .replace("https://", "wss://")
            .replace("http://", "ws://")
        val wsUrl = "$supabaseUrl/realtime/v1/websocket?apikey=${Constants.SUPABASE_ANON_KEY}&vsn=1.0.0"

        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // No timeout for WebSocket
            .pingInterval(25, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder().url(wsUrl).build()

        // Store the token so joinChannel can include it
        currentAccessToken = accessToken

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                // Join the channel for account_sync_state changes for this user
                joinChannel(webSocket, userId)
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}")
                scheduleReconnect()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code $reason")
                webSocket.close(1000, null)
                if (isRunning.get()) scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code")
                if (isRunning.get()) scheduleReconnect()
            }
        })
    }

    private fun joinChannel(ws: WebSocket, userId: String) {
        // Channel 1: account_sync_state UPDATEs (full snapshot pulls).
        // access_token is required for Supabase RLS to authenticate the subscription.
        val accountSyncJoin = JSONObject().apply {
            put("topic", "realtime:account_sync")
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("postgres_changes", JSONArray().apply {
                        put(JSONObject().apply {
                            put("event", "UPDATE")
                            put("schema", "public")
                            put("table", "account_sync_state")
                            put("filter", "user_id=eq.$userId")
                        })
                    })
                })
                currentAccessToken?.let { put("access_token", it) }
            })
            put("ref", msgRef.getAndIncrement().toString())
        }
        ws.send(accountSyncJoin.toString())

        // Channel 2: watch_history INSERT + UPDATE events for cross-device Continue
        // Watching refresh (#91). We subscribe to both INSERT (first watch of a new
        // item) and UPDATE (position/progress changes) so either event refreshes the
        // other device's Home row.
        val watchHistoryJoin = JSONObject().apply {
            put("topic", "realtime:watch_history")
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("postgres_changes", JSONArray().apply {
                        put(JSONObject().apply {
                            put("event", "INSERT")
                            put("schema", "public")
                            put("table", "watch_history")
                            put("filter", "user_id=eq.$userId")
                        })
                        put(JSONObject().apply {
                            put("event", "UPDATE")
                            put("schema", "public")
                            put("table", "watch_history")
                            put("filter", "user_id=eq.$userId")
                        })
                    })
                })
                currentAccessToken?.let { put("access_token", it) }
            })
            put("ref", msgRef.getAndIncrement().toString())
        }
        ws.send(watchHistoryJoin.toString())

        Log.i(TAG, "Joined account_sync + watch_history channels for user $userId")
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val event = msg.optString("event", "")
            val topic = msg.optString("topic", "")

            when (event) {
                "postgres_changes" -> {
                    // Route based on which channel sent the event.
                    when (topic) {
                        "realtime:account_sync" -> {
                            Log.i(TAG, "Received account_sync change")
                            debouncedPull()
                        }
                        "realtime:watch_history" -> {
                            Log.i(TAG, "Received watch_history change")
                            debouncedWatchHistoryEmit()
                        }
                        else -> {
                            Log.w(TAG, "postgres_changes on unknown topic: $topic")
                        }
                    }
                }
                "phx_reply" -> {
                    val status = msg.optJSONObject("payload")?.optString("status")
                    Log.d(TAG, "Channel reply ($topic): $status")
                }
                "phx_error" -> {
                    Log.w(TAG, "Channel error: $text")
                }
                "system" -> {
                    // System messages like subscription confirmation
                    val payload = msg.optJSONObject("payload")
                    if (payload?.optString("status") == "ok") {
                        Log.i(TAG, "Subscription confirmed ($topic)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse realtime message: ${e.message}")
        }
    }

    private fun debouncedPull() {
        // Skip if we just pushed (avoid pulling back our own changes)
        if (System.currentTimeMillis() - lastPushTimestamp < 3_000L) {
            Log.d(TAG, "Skipping pull - recent push detected")
            return
        }

        pendingPullJob?.cancel()
        pendingPullJob = scope.launch {
            delay(DEBOUNCE_MS)
            Log.i(TAG, "Pulling cloud state after realtime notification")
            runCatching { cloudSyncRepository.pullFromCloud() }
                .onFailure { Log.w(TAG, "Realtime pull failed: ${it.message}") }
        }
    }

    private fun debouncedWatchHistoryEmit() {
        // Skip if the event is almost certainly from our own recent write. This
        // catches the common case where the user is actively watching on device A
        // and A's periodic watch_history UPDATE fires back to A as a realtime event.
        if (System.currentTimeMillis() - lastLocalWatchHistoryWriteTimestamp < 3_000L) {
            Log.d(TAG, "Skipping watch_history emit - recent local write")
            return
        }

        pendingWatchHistoryEmitJob?.cancel()
        pendingWatchHistoryEmitJob = scope.launch {
            delay(WATCH_HISTORY_DEBOUNCE_MS)
            Log.i(TAG, "Emitting watch_history event for Home refresh")
            _watchHistoryEvents.tryEmit(Unit)
        }
    }

    // ── Heartbeat ───────────────────────────────────────────────────

    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(HEARTBEAT_INTERVAL_MS)
                val hb = JSONObject().apply {
                    put("topic", "phoenix")
                    put("event", "heartbeat")
                    put("payload", JSONObject())
                    put("ref", msgRef.getAndIncrement().toString())
                }
                try {
                    ws.send(hb.toString())
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    // ── Reconnect ───────────────────────────────────────────────────

    private fun scheduleReconnect() {
        heartbeatJob?.cancel()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (isRunning.get()) {
                Log.i(TAG, "Reconnecting WebSocket...")
                connectWebSocket()
            }
        }
    }

    // ── Periodic Fallback Sync ──────────────────────────────────────

    private fun startPeriodicSync() {
        periodicSyncJob?.cancel()
        periodicSyncJob = scope.launch {
            while (isActive && isRunning.get()) {
                delay(PERIODIC_SYNC_INTERVAL_MS)
                if (!isRunning.get()) break
                Log.d(TAG, "Periodic sync tick")
                runCatching { cloudSyncRepository.pullFromCloud() }
                    .onFailure { Log.w(TAG, "Periodic sync failed: ${it.message}") }
            }
        }
    }
}
