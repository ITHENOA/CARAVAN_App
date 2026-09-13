package com.example.data.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

sealed class CaravanWsEvent {
    data class Connected(val tripId: String) : CaravanWsEvent()
    data class Reconnecting(val attempt: Int) : CaravanWsEvent()
    data class Disconnected(val code: Int, val reason: String) : CaravanWsEvent()
    data class Error(val error: Throwable, val isNotFound: Boolean = false) : CaravanWsEvent()
    data class MessageReceived(val type: String, val raw: JSONObject) : CaravanWsEvent()
}

class CaravanWebSocketClient(
    private var wsBaseUrl: String = "wss://caravan-backend.ithenoa.workers.dev"
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isManualDisconnect = false
    private var currentTripId: String? = null
    private var coroutineScope: CoroutineScope? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    val isConnected: Boolean
        get() = webSocket != null && !isManualDisconnect

    private val _events = MutableSharedFlow<CaravanWsEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun updateWsBaseUrl(newBaseUrl: String) {
        wsBaseUrl = newBaseUrl.trimEnd('/')
    }

    fun connect(tripId: String, scope: CoroutineScope) {
        disconnect()
        isManualDisconnect = false
        currentTripId = tripId
        coroutineScope = scope
        reconnectAttempt = 0
        reconnectJob?.cancel()

        openSocket(tripId, scope)
    }

    private fun openSocket(tripId: String, scope: CoroutineScope) {
        val url = "$wsBaseUrl/trip/$tripId"
        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("CaravanWS", "Connected to $url")
                reconnectAttempt = 0
                reconnectJob?.cancel()
                scope.launch {
                    _events.emit(CaravanWsEvent.Connected(tripId))
                }
                startPingTimer(scope)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type", "")
                    scope.launch {
                        _events.emit(CaravanWsEvent.MessageReceived(type, json))
                    }
                } catch (e: Exception) {
                    Log.e("CaravanWS", "Failed to parse message: $text", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("CaravanWS", "Closed: $code, $reason")
                pingJob?.cancel()
                scope.launch {
                    _events.emit(CaravanWsEvent.Disconnected(code, reason))
                }
                if (code != 1000 && !isManualDisconnect) {
                    scheduleReconnect(scope)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val is404 = response?.code == 404
                Log.e("CaravanWS", "Failure: ${t.message}, httpCode=${response?.code}")
                pingJob?.cancel()
                scope.launch {
                    _events.emit(CaravanWsEvent.Error(t, isNotFound = is404))
                }
                if (!is404 && !isManualDisconnect) {
                    scheduleReconnect(scope)
                }
            }
        })
    }

    private fun scheduleReconnect(scope: CoroutineScope) {
        if (isManualDisconnect || currentTripId == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val delayMs = minOf(1000L * (1 shl minOf(reconnectAttempt - 1, 4)), 10_000L)
            Log.d("CaravanWS", "Scheduling reconnect attempt $reconnectAttempt in ${delayMs}ms")
            _events.emit(CaravanWsEvent.Reconnecting(reconnectAttempt))
            delay(delayMs)
            if (!isManualDisconnect && currentTripId != null) {
                openSocket(currentTripId!!, scope)
            }
        }
    }

    fun send(message: String): Boolean {
        return webSocket?.send(message) ?: false
    }

    private fun startPingTimer(scope: CoroutineScope) {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                delay(15_000)
                send(com.example.data.protocol.CaravanProtocol.buildPing())
            }
        }
    }

    fun disconnect() {
        isManualDisconnect = true
        pingJob?.cancel()
        webSocket?.close(1000, "Leaving")
        webSocket = null
    }
}
