package com.example.data.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
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
    private var proxy: Proxy? = null
    private var dns: Dns = Dns.SYSTEM
    private var client = buildClient()

    private var webSocket: WebSocket? = null
    private var isManualDisconnect = false
    private var currentTripId: String? = null
    private var coroutineScope: CoroutineScope? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0

    val isConnected: Boolean
        get() = webSocket != null && !isManualDisconnect

    // Audio arrives ~8 msgs/sec; keep a deep buffer so map work on Main can't stall emits.
    private val _events = MutableSharedFlow<CaravanWsEvent>(extraBufferCapacity = 512)
    val events = _events.asSharedFlow()

    fun updateWsBaseUrl(newBaseUrl: String) {
        wsBaseUrl = newBaseUrl.trimEnd('/')
    }

    fun updateDns(dns: Dns) {
        this.dns = dns
        client = buildClient()
    }

    /** See [CaravanApiClient.updateProxy]. Rebuilds the client; call [connect] again if already in a trip. */
    fun updateProxy(
        enabled: Boolean,
        host: String,
        port: Int,
        type: Proxy.Type = Proxy.Type.HTTP
    ) {
        try {
            proxy = if (enabled && host.isNotBlank() && port in 1..65535) {
                Proxy(type, InetSocketAddress.createUnresolved(host.trim(), port))
            } else {
                null
            }
            client = buildClient()
        } catch (e: Exception) {
            Log.e("CaravanWS", "updateProxy failed; falling back to system proxy", e)
            proxy = null
            client = buildClient()
        }
    }

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(dns)
        if (proxy != null) builder.proxy(proxy)
        return builder.build()
    }

    fun connect(tripId: String, scope: CoroutineScope) {
        disconnect(manual = true)
        isManualDisconnect = false
        currentTripId = tripId
        coroutineScope = scope
        reconnectAttempt = 0
        reconnectJob?.cancel()

        openSocket(tripId, scope)
    }

    private fun openSocket(tripId: String, scope: CoroutineScope) {
        webSocket?.cancel()
        webSocket = null

        val url = "$wsBaseUrl/trip/$tripId"
        val request = Request.Builder()
            .url(url)
            .build()

        Log.d("CaravanWS", "Opening $url")
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
                Log.e("CaravanWS", "Failure: ${t.message}, httpCode=${response?.code}", t)
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

    fun disconnect() = disconnect(manual = true)

    private fun disconnect(manual: Boolean) {
        isManualDisconnect = manual
        reconnectJob?.cancel()
        reconnectJob = null
        pingJob?.cancel()
        pingJob = null
        webSocket?.cancel()
        webSocket = null
    }
}
