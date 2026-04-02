package com.arflix.tv.data.repository

import android.util.Log
import com.arflix.tv.util.Constants
import kotlinx.coroutines.*
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
 * notifications when `account_sync_state` changes on any device.
 *
 * When a change is detected, it triggers [CloudSyncRepository.pullFromCloud]
 * so the local app state updates within 1-2 seconds of a remote push.
 *
 * Also runs a periodic fallback sync every 5 minutes in case the WebSocket
 * disconnects or misses an event.
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
        private const val PERIODIC_SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val DEBOUNCE_MS = 2_000L // Debounce pulls to avoid rapid-fire
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private val msgRef = AtomicInteger(1)

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var periodicSyncJob: Job? = null
    private var reconnectJob: Job? = null
    private var pendingPullJob: Job? = null

    // Track our own pushes to avoid pulling back what we just pushed
    @Volatile
    private var lastPushTimestamp = 0L

    // User JWT for authenticated Realtime subscriptions
    @Volatile
    private var currentAccessToken: String? = null

    fun markPush() {
        lastPushTimestamp = System.currentTimeMillis()
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
        // Subscribe to postgres_changes on account_sync_state table filtered by user_id
        // access_token is required for Supabase RLS to authenticate the subscription
        val joinMsg = JSONObject().apply {
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
        ws.send(joinMsg.toString())
        Log.i(TAG, "Joined channel for user $userId")
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JSONObject(text)
            val event = msg.optString("event", "")

            when (event) {
                "postgres_changes" -> {
                    // A change was detected on account_sync_state
                    Log.i(TAG, "Received sync change notification")
                    debouncedPull()
                }
                "phx_reply" -> {
                    val status = msg.optJSONObject("payload")?.optString("status")
                    Log.d(TAG, "Channel reply: $status")
                }
                "phx_error" -> {
                    Log.w(TAG, "Channel error: $text")
                }
                "system" -> {
                    // System messages like subscription confirmation
                    val payload = msg.optJSONObject("payload")
                    if (payload?.optString("status") == "ok") {
                        Log.i(TAG, "Subscription confirmed")
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
