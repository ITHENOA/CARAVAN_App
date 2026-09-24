package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.PreferencesManager
import com.example.data.location.DeviceLocation
import com.example.data.location.LocationProvider
import com.example.data.model.*
import com.example.data.network.*
import com.example.data.protocol.CaravanProtocol
import com.example.data.voice.PttState
import com.example.data.voice.VoicePttController
import com.example.data.update.AppUpdateManager
import com.example.data.update.UpdateDownloadState
import com.example.data.update.UpdateInfo
import com.example.push.PushRegistrar
import com.example.util.ConvoyUtils
import com.example.util.InviteQr
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

enum class CaravanConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}

enum class RoutingProvider {
    OSRM,
    NESHAN
}

data class ChatPreviewItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val senderName: String,
    val text: String,
    val avatarColor: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ConnectionFixStep {
    /** Offer one-tap Google DNS. */
    TRY_GOOGLE_DNS,
    /** Google DNS already tried — offer Aether. */
    TRY_AETHER,
    /** Aether also failed — open Settings / use VPN. */
    OPEN_SETTINGS_OR_VPN
}

data class TripUiState(
    val inTrip: Boolean = false,
    val tripId: String = "",
    val inviteCode: String = "",
    val tripName: String = "Caravan Trip",
    val isLeader: Boolean = false,
    val leaderToken: String? = null,
    val connectionStatus: CaravanConnectionStatus = CaravanConnectionStatus.DISCONNECTED,
    val connectionError: String? = null,
    /** Progressive fix offered on the amber connection banner. */
    val connectionFixStep: ConnectionFixStep = ConnectionFixStep.TRY_GOOGLE_DNS,
    val members: List<TripMember> = emptyList(),
    val destination: TripDestination? = null,
    val route: RouteResult? = null,
    /** Null until the user picks Regular or Neshan on the destination HUD. */
    val routingProvider: RoutingProvider? = null,
    val isCalculatingRoute: Boolean = false,
    val isNavigating: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val latestAlert: ChatMessage? = null,
    val visiblePreviews: List<ChatPreviewItem> = emptyList(),
    val sharedRoutes: Map<String, SharedRoute> = emptyMap(),
    val marks: Map<String, MapMark> = emptyMap(),
    val activeSpeakerName: String? = null,
    val pttState: PttState = PttState.IDLE,
    val audioAmplitude: Float = 0f,
    val voiceNoteRecording: Boolean = false,
    val voiceNoteReady: Boolean = false,
    val kickedReason: String? = null
)

class CaravanViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        /** Match server: ignore marks older than a drive session on join. */
        private const val MARK_TTL_MS = 30L * 60L * 1000L
        /** No location update → show as reconnecting (stale pin + time ago). */
        private const val RECONNECTING_AFTER_MS = 60_000L
        /** Still no location → treat as offline but keep last pin (5 minutes per user requirement). */
        private const val OFFLINE_AFTER_MS = 300_000L
    }

    val prefs = PreferencesManager(application)
    val apiClient = CaravanApiClient(prefs.apiBaseUrl)
    val wsClient = CaravanWebSocketClient(prefs.wsBaseUrl)
    private var pendingVoiceNote: ByteArray? = null
    val locationProvider = LocationProvider(application)
    val pttController = VoicePttController(application)
    val mutedPeerIds: StateFlow<Set<String>> = pttController.mutedPeerIds

    fun togglePeerMute(peerId: String) = pttController.togglePeerMute(peerId)

    fun setAllPeersMuted(peerIds: Iterable<String>, muted: Boolean) =
        pttController.setAllPeersMuted(peerIds, muted)

    private val _isDarkMode = MutableStateFlow(prefs.isDarkMode)
    val isDarkMode: StateFlow<Boolean> = _isDarkMode.asStateFlow()

    fun toggleDarkMode() {
        val next = !_isDarkMode.value
        _isDarkMode.value = next
        prefs.isDarkMode = next
    }

    fun setDarkMode(enabled: Boolean) {
        _isDarkMode.value = enabled
        prefs.isDarkMode = enabled
    }

    private val _mapTheme = MutableStateFlow(prefs.mapTheme)
    val mapTheme: StateFlow<String> = _mapTheme.asStateFlow()

    fun setMapTheme(theme: String) {
        _mapTheme.value = theme
        prefs.mapTheme = theme
    }

    private val _drivingViewZoom = MutableStateFlow(prefs.drivingViewZoom)
    val drivingViewZoom: StateFlow<Float> = _drivingViewZoom.asStateFlow()

    private val _drivingMarkerPosition = MutableStateFlow(prefs.drivingMarkerPosition)
    val drivingMarkerPosition: StateFlow<Float> = _drivingMarkerPosition.asStateFlow()

    private val _convoyFramingRadiusMeters = MutableStateFlow(prefs.convoyFramingRadiusMeters)
    val convoyFramingRadiusMeters: StateFlow<Int> = _convoyFramingRadiusMeters.asStateFlow()

    fun updateDrivingViewZoom(zoom: Float) {
        prefs.drivingViewZoom = zoom
        _drivingViewZoom.value = zoom
    }

    fun updateDrivingMarkerPosition(position: Float) {
        prefs.drivingMarkerPosition = position
        _drivingMarkerPosition.value = position
    }

    fun updateConvoyFramingRadiusMeters(meters: Int) {
        prefs.convoyFramingRadiusMeters = meters
        _convoyFramingRadiusMeters.value = meters
    }

    /** Apply saved proxy + DNS prefs to HTTP + WebSocket clients. Safe on main thread. */
    fun applyProxySettings() {
        val dns = if (prefs.isDnsEnabled) {
            com.example.data.network.DnsPresets.resolveOkHttpDns(
                prefs.dnsPreset,
                prefs.dnsCustomPrimary,
                prefs.dnsCustomSecondary
            )
        } else {
            okhttp3.Dns.SYSTEM
        }
        apiClient.updateDns(dns)
        wsClient.updateDns(dns)

        when {
            prefs.useAetherProxy -> {
                apiClient.updateProxy(
                    true,
                    com.example.data.network.AetherHelper.SOCKS_HOST,
                    com.example.data.network.AetherHelper.SOCKS_PORT,
                    java.net.Proxy.Type.SOCKS
                )
                wsClient.updateProxy(
                    true,
                    com.example.data.network.AetherHelper.SOCKS_HOST,
                    com.example.data.network.AetherHelper.SOCKS_PORT,
                    java.net.Proxy.Type.SOCKS
                )
            }
            prefs.isProxyEnabled -> {
                val type = if (prefs.proxyType.equals("socks", ignoreCase = true)) {
                    java.net.Proxy.Type.SOCKS
                } else {
                    java.net.Proxy.Type.HTTP
                }
                apiClient.updateProxy(true, prefs.proxyHost, prefs.proxyPort, type)
                wsClient.updateProxy(true, prefs.proxyHost, prefs.proxyPort, type)
            }
            else -> {
                apiClient.updateProxy(false, "", 0)
                wsClient.updateProxy(false, "", 0)
            }
        }
    }

    /** Last fix the user tapped on the amber banner (−1 = none, 0 = Google DNS, 1 = Aether). */
    private var lastAppliedConnectionFix: Int = -1
    private var suppressConnectionFailureUntil = 0L
    private var delayedConnectionFailure: Job? = null

    fun reconnectCurrentTrip() {
        val state = _tripState.value
        if (!state.inTrip || state.tripId.isBlank()) return
        applyProxySettings()
        wsClient.updateWsBaseUrl(prefs.wsBaseUrl)
        _tripState.update {
            it.copy(
                connectionStatus = CaravanConnectionStatus.CONNECTING,
                connectionError = "Reconnecting…"
            )
        }
        wsClient.connect(state.tripId, viewModelScope)
    }

    /**
     * Progressive recovery from the amber WebSocket banner:
     * Google DNS → Aether (wg/turbo/IPv4/off) → Settings/VPN.
     */
    fun applySuggestedConnectionFix(context: android.content.Context, onOpenSettings: () -> Unit) {
        suppressConnectionFailureUntil = System.currentTimeMillis() + 2_500L
        delayedConnectionFailure?.cancel()
        when (_tripState.value.connectionFixStep) {
            ConnectionFixStep.TRY_GOOGLE_DNS -> {
                prefs.isDnsEnabled = true
                prefs.dnsPreset = "google"
                prefs.useAetherProxy = false
                applyProxySettings()
                lastAppliedConnectionFix = 0
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.CONNECTING,
                        connectionError = "Trying Google DNS (8.8.8.8)…"
                    )
                }
                reconnectCurrentTrip()
            }
            ConnectionFixStep.TRY_AETHER -> {
                prefs.isDnsEnabled = false
                prefs.dnsPreset = "system"
                prefs.isProxyEnabled = false
                prefs.aetherProtocol = "wg"
                prefs.aetherScan = "turbo"
                prefs.aetherIpMode = "4"
                prefs.aetherNoize = "off"
                prefs.useAetherProxy = true
                lastAppliedConnectionFix = 1
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.CONNECTING,
                        connectionError = "Starting Aether (WireGuard)…"
                    )
                }
                viewModelScope.launch {
                    val listening = com.example.data.network.AetherHelper.isProxyListening()
                    if (!listening) {
                        val cmd = com.example.data.network.AetherHelper.buildStartCommand(
                            protocol = "wg",
                            scan = "turbo",
                            noize = "off",
                            ipMode = "4"
                        )
                        withContext(Dispatchers.Main) {
                            com.example.data.network.AetherHelper.runInTermux(context, cmd)
                        }
                        repeat(15) {
                            delay(1_000L)
                            if (com.example.data.network.AetherHelper.isProxyListening()) return@repeat
                        }
                    }
                    applyProxySettings()
                    if (com.example.data.network.AetherHelper.isProxyListening()) {
                        _tripState.update {
                            it.copy(connectionError = "Connecting via Aether…")
                        }
                        reconnectCurrentTrip()
                    } else {
                        lastAppliedConnectionFix = 1
                        _tripState.update {
                            it.copy(
                                connectionStatus = CaravanConnectionStatus.ERROR,
                                connectionError = "Aether port :1819 not up yet — check Termux, or open Settings",
                                connectionFixStep = ConnectionFixStep.OPEN_SETTINGS_OR_VPN
                            )
                        }
                    }
                }
            }
            ConnectionFixStep.OPEN_SETTINGS_OR_VPN -> {
                onOpenSettings()
            }
        }
    }

    private fun advanceFixStepAfterFailure() {
        when (lastAppliedConnectionFix) {
            0 -> {
                // Google DNS didn't help — drop custom DNS, offer Aether next.
                prefs.dnsPreset = "system"
                applyProxySettings()
                _tripState.update { it.copy(connectionFixStep = ConnectionFixStep.TRY_AETHER) }
            }
            1 -> {
                _tripState.update { it.copy(connectionFixStep = ConnectionFixStep.OPEN_SETTINGS_OR_VPN) }
            }
            else -> Unit
        }
    }

    private val _userProfile = MutableStateFlow(prefs.getUserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _savedTrips = MutableStateFlow(prefs.getSavedTrips())
    val savedTrips: StateFlow<List<SavedTrip>> = _savedTrips.asStateFlow()

    private fun rememberTrip(trip: SavedTrip) {
        prefs.saveTrip(trip)
        _savedTrips.value = prefs.getSavedTrips()
    }

    private val _tripState = MutableStateFlow(TripUiState())
    val tripState: StateFlow<TripUiState> = _tripState.asStateFlow()

    val currentLocation: StateFlow<DeviceLocation> = locationProvider.currentLocation

    /** Google Maps / Neshan style: my-location overlay starts OFF until user taps GPS. */
    private val _isMyLocationActive = MutableStateFlow(false)
    val isMyLocationActive: StateFlow<Boolean> = _isMyLocationActive.asStateFlow()

    private val remoteMembers = MutableStateFlow<Map<String, TripMember>>(emptyMap())
    private var locationSyncJob: Job? = null
    /** Bumped to invalidate in-flight route calculations when dest/route changes. */
    private var routeEpoch = 0
    /** Serialize PCM playback so chunks stay in order off the Main thread. */
    private val audioPlaybackDispatcher = Dispatchers.IO.limitedParallelism(1)

    init {
        applyProxySettings()

        com.example.service.CaravanTripForegroundService.onLeaveRequested = {
            viewModelScope.launch(Dispatchers.Main) {
                leaveTrip()
            }
        }

        viewModelScope.launch {
            _tripState.collect { state ->
                if (state.inTrip) {
                    com.example.service.CaravanTripForegroundService.update(
                        getApplication(),
                        state.tripName,
                        state.members.size.coerceAtLeast(1)
                    )
                }
            }
        }

        // Listen to WebSocket events
        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }

        // Hook up PTT audio streaming to broadcast live voice chunks over WebSocket.
        // Gate on the controller state (not lagged UI copy) so the first chunks aren't dropped.
        pttController.isSoundEnabled = { prefs.isSoundEnabled }
        pttController.isHapticsEnabled = { prefs.isHapticsEnabled }
        var audioSeq = 0
        pttController.onAudioChunkCaptured = { pcmBytes ->
            try {
                val b64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
                val msg = CaravanProtocol.buildAudioChunk(b64, 16000, audioSeq++)
                if (!wsClient.send(msg)) {
                    android.util.Log.w("CaravanVM", "audio_chunk send failed (socket busy/closed), seq=${audioSeq - 1}")
                }
            } catch (e: Exception) {
                android.util.Log.e("CaravanVM", "Failed to stream audio chunk", e)
            }
        }
        pttController.onLiveTransmissionEnded = {
            audioSeq = 0
            if (_tripState.value.inTrip) {
                wsClient.send(CaravanProtocol.buildPttRelease())
            }
        }
        pttController.onVoiceNoteAutoStop = {
            viewModelScope.launch { finishVoiceNoteRecording() }
        }

        // Local GPS/presence staleness: keep last known position, mark reconnecting/offline for UI.
        viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                refreshMemberPresence()
            }
        }

        // Combine remote members with mock fleet (if enabled) and ensure strictly distinct colors for everyone in group
        viewModelScope.launch {
            combine(remoteMembers, locationProvider.mockFleet) { remote, mock ->
                val combined = remote.values.toMutableList()
                if (prefs.isMockFleetEnabled) {
                    mock.forEach { mockCar ->
                        if (!remote.containsKey(mockCar.id)) {
                            combined.add(mockCar)
                        }
                    }
                }
                combined
            }.collect { memberList ->
                val selfId = _userProfile.value.clientId
                val (resolvedSelfColor, coloredMembers) = ConvoyUtils.resolveDistinctColors(
                    selfId = selfId,
                    selfPreferredColor = _userProfile.value.avatarColor,
                    members = memberList
                )
                if (ConvoyUtils.normalizeHex(resolvedSelfColor) !=
                    ConvoyUtils.normalizeHex(_userProfile.value.avatarColor)
                ) {
                    val updatedProfile = _userProfile.value.copy(avatarColor = resolvedSelfColor)
                    _userProfile.value = updatedProfile
                    prefs.saveUserProfile(updatedProfile)
                }
                // Keep GPS / DEST / route colors locked to each owner's unique color
                val colorById = coloredMembers.associate { it.id to (it.avatarColor ?: resolvedSelfColor) }
                    .toMutableMap()
                colorById[selfId] = resolvedSelfColor

                _tripState.update { state ->
                    val updatedRoutes = state.sharedRoutes.mapValues { (cid, route) ->
                        // Neshan traffic routes keep green/orange/red — do not remint to avatar color
                        if (route.segments.isNotEmpty()) route
                        else {
                            val c = colorById[cid] ?: route.colorHex
                            if (ConvoyUtils.normalizeHex(c) != ConvoyUtils.normalizeHex(route.colorHex)) {
                                route.copy(colorHex = c)
                            } else route
                        }
                    }
                    val updatedMarks = state.marks.mapValues { (cid, mark) ->
                        val c = colorById[cid] ?: mark.color
                        if (ConvoyUtils.normalizeHex(c) != ConvoyUtils.normalizeHex(mark.color)) {
                            mark.copy(color = c)
                        } else mark
                    }
                    val dest = state.destination
                    val updatedDest = if (dest != null) {
                        val ownerId = dest.updatedById.ifBlank { selfId }
                        val c = colorById[ownerId] ?: dest.colorHex
                        if (c != null &&
                            ConvoyUtils.normalizeHex(c) != ConvoyUtils.normalizeHex(dest.colorHex)
                        ) {
                            dest.copy(colorHex = c)
                        } else dest
                    } else null

                    state.copy(
                        members = coloredMembers,
                        sharedRoutes = updatedRoutes,
                        marks = updatedMarks,
                        destination = updatedDest
                    )
                }
            }
        }

        // Forward PTT states and audio amplitude
        viewModelScope.launch {
            combine(
                pttController.pttState,
                pttController.activeSpeakerName,
                combine(pttController.audioAmplitude, pttController.voiceNoteRecording, ::Pair)
            ) { state, speaker, ampAndNote ->
                Triple(state, speaker, ampAndNote)
            }.collect { (state, speaker, ampAndNote) ->
                _tripState.update {
                    it.copy(
                        pttState = state,
                        activeSpeakerName = speaker,
                        audioAmplitude = ampAndNote.first,
                        voiceNoteRecording = ampAndNote.second
                    )
                }
            }
        }

        // Monitor route deviation during navigation and automatically update path
        viewModelScope.launch {
            var consecutiveDeviationCount = 0
            var lastRecalcTime = 0L

            currentLocation.collect { loc ->
                val state = _tripState.value
                val dest = state.destination
                val currentRoute = state.route
                val now = System.currentTimeMillis()

                if (state.isNavigating && dest != null && currentRoute != null && !state.isCalculatingRoute) {
                    if (now - lastRecalcTime > 5000L && currentRoute.points.size >= 2) {
                        val distToRoute = ConvoyUtils.minDistanceToPolyline(
                            lat = loc.latitude,
                            lng = loc.longitude,
                            points = currentRoute.points
                        )
                        // Off-route threshold: 40 meters
                        if (distToRoute > 40.0) {
                            consecutiveDeviationCount++
                            if (consecutiveDeviationCount >= 2) {
                                consecutiveDeviationCount = 0
                                lastRecalcTime = now
                                val provider = state.routingProvider
                                if (provider != null) {
                                    android.util.Log.i("Caravan", "Off-route detected (${distToRoute.toInt()}m). Auto-updating route using $provider...")
                                    calculateRouteWithProvider(provider)
                                }
                            }
                        } else {
                            consecutiveDeviationCount = 0
                        }
                    }
                }
            }
        }

        // Do not auto-start GPS or show self marker — wait for the GPS button (Maps/Neshan style).
    }

    fun setManualLocation(lat: Double, lng: Double) {
        locationProvider.setManualLocation(lat, lng)
        val me = currentLocation.value
        if (_tripState.value.inTrip) {
            val msg = CaravanProtocol.buildLocationUpdate(
                latitude = lat,
                longitude = lng,
                speed = me.speed,
                heading = me.heading,
                accuracy = me.accuracy
            )
            wsClient.send(msg)
        }
    }

    /**
     * GPS FAB: off → turn on tracking + show self marker (if system location is on).
     * Already on → request a fresh fix (map recenters).
     * @return false when system location is disabled (caller should open settings).
     */
    fun onMyLocationButtonClick(): Boolean {
        if (!locationProvider.isGpsEnabled()) {
            return false
        }
        if (!_isMyLocationActive.value) {
            _isMyLocationActive.value = true
        }
        locationProvider.requestImmediateLocation()
        return true
    }

    fun refreshGps() {
        onMyLocationButtonClick()
    }

    fun deactivateMyLocation() {
        _isMyLocationActive.value = false
    }

    fun updateProfile(displayName: String, carName: String, avatarColor: String, memberKind: MemberKind = MemberKind.VEHICLE) {
        val normalized = ConvoyUtils.normalizeHex(avatarColor).ifEmpty { ConvoyUtils.palette[0] }
        val kind = memberKind
        val updated = _userProfile.value.copy(
            displayName = displayName.trim().ifEmpty { if (kind == MemberKind.PERSON) "Member" else "Driver" },
            carName = if (kind == MemberKind.PERSON) "" else carName.trim().ifEmpty { "SUV" },
            memberKind = kind,
            avatarColor = normalized
        )
        _userProfile.value = updated
        prefs.saveUserProfile(updated)
    }

    fun createTrip(name: String, onComplete: (Boolean) -> Unit) {
        val tripName = name.trim().ifEmpty { "${_userProfile.value.displayName}'s Convoy" }
        _tripState.update {
            it.copy(
                inTrip = true,
                tripName = tripName,
                connectionStatus = CaravanConnectionStatus.CONNECTING
            )
        }

        viewModelScope.launch {
            val res = apiClient.createTrip(
                name = tripName,
                displayName = _userProfile.value.displayName,
                clientId = _userProfile.value.clientId
            )
            res.onSuccess { result ->
                val saved = SavedTrip(
                    tripId = result.tripId,
                    inviteCode = result.inviteCode,
                    tripName = result.name,
                    leaderToken = result.leaderToken,
                    isLeader = true
                )
                rememberTrip(saved)

                _tripState.update {
                    it.copy(
                        tripId = result.tripId,
                        inviteCode = result.inviteCode,
                        tripName = result.name,
                        isLeader = true,
                        leaderToken = result.leaderToken
                    )
                }

                connectToTrip(result.tripId, result.inviteCode, result.leaderToken)
                onComplete(true)
            }.onFailure {
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.ERROR,
                        connectionError = "Failed to create trip"
                    )
                }
                onComplete(false)
            }
        }
    }

    fun joinTrip(codeOrUrl: String, onComplete: (Boolean) -> Unit) {
        val inviteCode = InviteQr.parseInviteCode(codeOrUrl)
        if (inviteCode.isBlank()) {
            onComplete(false)
            return
        }

        _tripState.update {
            it.copy(
                inTrip = true,
                connectionStatus = CaravanConnectionStatus.CONNECTING
            )
        }

        viewModelScope.launch {
            val lookupRes = apiClient.lookupTrip(inviteCode)
            lookupRes.onSuccess { tripId ->
                val saved = SavedTrip(
                    tripId = tripId,
                    inviteCode = inviteCode,
                    tripName = "Joining…",
                    isLeader = false
                )
                rememberTrip(saved)

                _tripState.update {
                    it.copy(
                        tripId = tripId,
                        inviteCode = inviteCode,
                        tripName = "Joining…",
                        isLeader = false
                    )
                }

                connectToTrip(tripId, inviteCode, null)
                onComplete(true)
            }.onFailure {
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.ERROR,
                        connectionError = "Trip not found"
                    )
                }
                onComplete(false)
            }
        }
    }

    fun rejoinSavedTrip(trip: SavedTrip) {
        _tripState.update {
            it.copy(
                inTrip = true,
                tripId = trip.tripId,
                inviteCode = trip.inviteCode,
                tripName = trip.tripName,
                isLeader = trip.isLeader,
                leaderToken = trip.leaderToken,
                connectionStatus = CaravanConnectionStatus.CONNECTING
            )
        }
        rememberTrip(trip) // bump to top of list
        connectToTrip(trip.tripId, trip.inviteCode, trip.leaderToken)
    }

    private fun connectToTrip(tripId: String, inviteCode: String, leaderToken: String?) {
        wsClient.updateWsBaseUrl(prefs.wsBaseUrl)
        wsClient.connect(tripId, viewModelScope)

        com.example.service.CaravanTripForegroundService.start(
            getApplication(),
            _tripState.value.tripName,
            _tripState.value.members.size.coerceAtLeast(1)
        )

        if (prefs.isMockFleetEnabled) {
            locationProvider.startMockFleet(viewModelScope)
        }

        // Background GPS for convoy sharing only — UI marker stays off until GPS button.
        locationProvider.startLocationUpdates(seedFromLastKnown = true)

        // Start sending location updates periodically
        startLocationBroadcaster()

        // If WSS never opens (some mobile ISPs black-hole the handshake), don't sit on Connecting forever.
        viewModelScope.launch {
            delay(18_000L)
            val state = _tripState.value
            if (state.inTrip &&
                state.tripId == tripId &&
                state.connectionStatus == CaravanConnectionStatus.CONNECTING
            ) {
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.RECONNECTING,
                        connectionError = "WebSocket slow on this network — retrying (${prefs.wsBaseUrl})"
                    )
                }
                advanceFixStepAfterFailure()
            }
        }
    }

    private fun startLocationBroadcaster() {
        locationSyncJob?.cancel()
        locationSyncJob = viewModelScope.launch {
            var lastSentLat = 0.0
            var lastSentLng = 0.0
            var lastSentHeading = 0.0
            var lastSentTime = 0L

            // Background & stationary presence heartbeat (guarantees peers always see user online even if parked/screen off)
            launch {
                while (isActive) {
                    delay(5_000L)
                    val now = System.currentTimeMillis()
                    if (_tripState.value.inTrip && (now - lastSentTime >= 5_000L)) {
                        val loc = currentLocation.value
                        if (loc.isRealGps || (loc.latitude != 0.0 && loc.longitude != 0.0)) {
                            val msg = CaravanProtocol.buildLocationUpdate(
                                latitude = loc.latitude,
                                longitude = loc.longitude,
                                accuracy = loc.accuracy,
                                speed = loc.speed,
                                heading = loc.heading
                            )
                            if (wsClient.send(msg)) {
                                lastSentTime = now
                            }
                        }
                    }
                }
            }

            // Reactive collection of location fixes for true real-time GPS synchronization
            currentLocation.collect { loc ->
                if (!_tripState.value.inTrip) return@collect
                if (!loc.isRealGps && (loc.latitude == 0.0 && loc.longitude == 0.0)) return@collect

                val now = System.currentTimeMillis()
                val elapsed = now - lastSentTime

                val results = FloatArray(1)
                android.location.Location.distanceBetween(
                    lastSentLat, lastSentLng,
                    loc.latitude, loc.longitude,
                    results
                )
                val distanceMoved = results[0]
                val headingDiff = kotlin.math.abs((loc.heading - lastSentHeading + 540.0) % 360.0 - 180.0)

                // Transmit in real-time if moved, turned, or heartbeat (1500ms max idle)
                val isSignificantMove = (elapsed >= 600L && (distanceMoved >= 1.5f || headingDiff >= 5.0))
                val isHeartbeat = (elapsed >= 1500L)
                val isInitial = (lastSentTime == 0L)

                if (isInitial || isSignificantMove || isHeartbeat) {
                    val msg = CaravanProtocol.buildLocationUpdate(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        speed = loc.speed,
                        heading = loc.heading
                    )
                    if (wsClient.send(msg)) {
                        lastSentLat = loc.latitude
                        lastSentLng = loc.longitude
                        lastSentHeading = loc.heading
                        lastSentTime = now
                    }
                }
            }
        }
    }

    private fun handleWsEvent(event: CaravanWsEvent) {
        when (event) {
            is CaravanWsEvent.Connected -> {
                lastAppliedConnectionFix = -1
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.CONNECTED,
                        connectionError = null,
                        connectionFixStep = ConnectionFixStep.TRY_GOOGLE_DNS
                    )
                }
                // Immediately send join packet over the live connection
                val current = _tripState.value
                val saved = _savedTrips.value.find { it.tripId == current.tripId }
                val invite = current.inviteCode.ifEmpty { saved?.inviteCode ?: "" }
                val token = current.leaderToken ?: saved?.leaderToken
                val joinMsg = CaravanProtocol.buildJoin(
                    clientId = _userProfile.value.clientId,
                    displayName = _userProfile.value.displayName,
                    inviteCode = invite,
                    carName = _userProfile.value.carName,
                    memberKind = _userProfile.value.memberKind,
                    avatarColor = _userProfile.value.avatarColor,
                    leaderToken = token
                )
                wsClient.send(joinMsg)

                // Send immediate location
                val loc = currentLocation.value
                val locMsg = CaravanProtocol.buildLocationUpdate(
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracy = loc.accuracy,
                    speed = loc.speed,
                    heading = loc.heading
                )
                wsClient.send(locMsg)
            }
            is CaravanWsEvent.Reconnecting -> {
                _tripState.update {
                    it.copy(
                        connectionStatus = CaravanConnectionStatus.RECONNECTING,
                        connectionError = it.connectionError ?: "Reconnecting to convoy…"
                    )
                }
            }
            is CaravanWsEvent.Disconnected -> {
                _tripState.update { it.copy(connectionStatus = CaravanConnectionStatus.DISCONNECTED) }
            }
            is CaravanWsEvent.Error -> {
                if (event.isNotFound) {
                    // Keep trip in the saved list — user decides when to delete.
                    _tripState.update {
                        it.copy(
                            connectionStatus = CaravanConnectionStatus.ERROR,
                            connectionError = "Trip not found on server or has expired."
                        )
                    }
                } else {
                    val publishFailure = {
                        advanceFixStepAfterFailure()
                        val reason = event.error.message?.take(100) ?: "connection failed"
                        val hint = when (_tripState.value.connectionFixStep) {
                            ConnectionFixStep.TRY_GOOGLE_DNS -> " — tap Google DNS"
                            ConnectionFixStep.TRY_AETHER -> " — tap Aether"
                            ConnectionFixStep.OPEN_SETTINGS_OR_VPN -> " — turn on VPN or open Settings"
                        }
                        _tripState.update {
                            it.copy(
                                connectionStatus = CaravanConnectionStatus.RECONNECTING,
                                connectionError = "Network: $reason$hint"
                            )
                        }
                    }
                    val remaining = suppressConnectionFailureUntil - System.currentTimeMillis()
                    if (remaining > 0) {
                        delayedConnectionFailure = viewModelScope.launch {
                            delay(remaining)
                            if (_tripState.value.connectionStatus != CaravanConnectionStatus.CONNECTED) {
                                publishFailure()
                            }
                        }
                    } else {
                        publishFailure()
                    }
                }
            }
            is CaravanWsEvent.MessageReceived -> {
                try {
                    handleProtocolMessage(event.type, event.raw)
                } catch (e: Throwable) {
                    android.util.Log.e("CaravanVM", "Error handling websocket message: ${event.type}", e)
                }
            }
        }
    }

    private fun handleProtocolMessage(type: String, json: JSONObject) {
        when (type) {
            "joined" -> {
                val leaderId = json.optString("leaderId", "")
                val serverTripName = json.optString("tripName", "").trim()
                val serverInvite = json.optString("inviteCode", "").trim()
                if (serverTripName.isNotEmpty() || serverInvite.isNotEmpty()) {
                    _tripState.update { state ->
                        state.copy(
                            tripName = serverTripName.ifEmpty { state.tripName },
                            inviteCode = serverInvite.ifEmpty { state.inviteCode }
                        )
                    }
                    val current = _tripState.value
                    if (current.tripId.isNotEmpty()) {
                        rememberTrip(
                            SavedTrip(
                                tripId = current.tripId,
                                inviteCode = current.inviteCode,
                                tripName = current.tripName,
                                leaderToken = current.leaderToken,
                                isLeader = current.isLeader
                            )
                        )
                    }
                }
                val membersArr = json.optJSONArray("members")
                if (membersArr != null) {
                    val list = CaravanProtocol.parseMembersList(membersArr, leaderId)
                    remoteMembers.update { current ->
                        mergeMembersPreservingLocation(current, list.associateBy { it.id })
                    }
                }
                if (json.has("destination") && !json.isNull("destination")) {
                    val destObj = json.optJSONObject("destination")
                    if (destObj != null) {
                        val dest = CaravanProtocol.parseDestination(destObj)
                        if (dest != null) setDestinationInternal(dest)
                    }
                }
                if (json.has("marks") && !json.isNull("marks")) {
                    val marksArr = json.optJSONArray("marks")
                    if (marksArr != null) {
                        val now = System.currentTimeMillis()
                        val markMap = mutableMapOf<String, MapMark>()
                        for (i in 0 until marksArr.length()) {
                            val mObj = marksArr.optJSONObject(i)
                            if (mObj != null) {
                                val m = CaravanProtocol.parseMapMark(mObj)
                                // Ignore ancient pins revived by reconnect to a long-lived room.
                                if (m != null && m.clientId.isNotEmpty() &&
                                    now - m.updatedAt <= MARK_TTL_MS
                                ) {
                                    markMap[m.clientId] = m
                                }
                            }
                        }
                        _tripState.update { it.copy(marks = markMap) }
                    }
                }
                if (json.has("routes") && !json.isNull("routes")) {
                    val routesArr = json.optJSONArray("routes")
                    if (routesArr != null) {
                        val routeMap = mutableMapOf<String, SharedRoute>()
                        for (i in 0 until routesArr.length()) {
                            val rObj = routesArr.optJSONObject(i)
                            if (rObj != null) {
                                val r = CaravanProtocol.parseSharedRoute(rObj)
                                if (r.clientId.isNotEmpty() && r.points.isNotEmpty()) {
                                    routeMap[r.clientId] = if (r.segments.isNotEmpty()) {
                                        r
                                    } else {
                                        r.copy(colorHex = ownerColor(r.clientId, r.colorHex))
                                    }
                                }
                            }
                        }
                        _tripState.update { it.copy(sharedRoutes = routeMap) }
                    }
                }
                registerPushTokenAfterJoin()
            }
            "member_joined" -> {
                val mJson = json.optJSONObject("member") ?: json
                val member = CaravanProtocol.parseMember(mJson)
                remoteMembers.update { it + (member.id to member) }
            }
            "member_left" -> {
                val cid = json.optString("clientId", "")
                if (cid.isNotEmpty()) {
                    // Keep last known lat/lng so the map still shows a stale pin ("Xm ago").
                    remoteMembers.update { current ->
                        val existing = current[cid] ?: return@update current
                        current + (cid to existing.copy(
                            connectionStatus = MemberConnectionStatus.OFFLINE,
                            lastSeenAt = System.currentTimeMillis(),
                            speed = 0.0
                        ))
                    }
                    _tripState.update { it.copy(marks = it.marks - cid, sharedRoutes = it.sharedRoutes - cid) }
                }
            }
            "members_snapshot" -> {
                val membersArr = json.optJSONArray("members")
                if (membersArr != null) {
                    val list = CaravanProtocol.parseMembersList(membersArr, null)
                    remoteMembers.update { current ->
                        mergeMembersPreservingLocation(current, list.associateBy { it.id })
                    }
                }
            }
            "map_mark" -> {
                val mObj = json.optJSONObject("mark") ?: json
                val mark = CaravanProtocol.parseMapMark(mObj)
                if (mark != null && mark.clientId.isNotEmpty()) {
                    val colored = mark.copy(color = ownerColor(mark.clientId, mark.color))
                    _tripState.update { it.copy(marks = it.marks + (mark.clientId to colored)) }
                }
            }
            "map_mark_clear" -> {
                val cid = json.optString("clientId", "")
                if (cid.isNotEmpty()) {
                    _tripState.update { it.copy(marks = it.marks - cid) }
                }
            }
            "route_update" -> {
                val shared = CaravanProtocol.parseSharedRoute(json)
                if (shared.clientId.isNotEmpty()) {
                    val colored = if (shared.segments.isNotEmpty()) {
                        shared // Neshan traffic colors stay as-is for all viewers
                    } else {
                        shared.copy(colorHex = ownerColor(shared.clientId, shared.colorHex))
                    }
                    _tripState.update { it.copy(sharedRoutes = it.sharedRoutes + (shared.clientId to colored)) }
                }
            }
            "route_clear" -> {
                val cid = json.optString("clientId", "")
                if (cid.isNotEmpty()) {
                    _tripState.update { it.copy(sharedRoutes = it.sharedRoutes - cid) }
                }
            }
            "location_update" -> {
                val cid = json.optString("clientId", "")
                if (cid.isNotEmpty() && cid != _userProfile.value.clientId) {
                    remoteMembers.update { current ->
                        val existing = current[cid]
                        if (existing != null) {
                            val updated = existing.copyWith(
                                latitude = json.optDouble("latitude"),
                                longitude = json.optDouble("longitude"),
                                speed = if (json.has("speed")) json.optDouble("speed") else existing.speed,
                                heading = if (json.has("heading")) json.optDouble("heading") else existing.heading,
                                accuracy = if (json.has("accuracy")) json.optDouble("accuracy") else existing.accuracy,
                                lastLocationAt = json.optLong("timestamp", System.currentTimeMillis()),
                                lastSeenAt = System.currentTimeMillis(),
                                connectionStatus = MemberConnectionStatus.CONNECTED
                            )
                            current + (cid to updated)
                        } else {
                            val newMember = TripMember(
                                id = cid,
                                displayName = json.optString("displayName", "Driver"),
                                avatarColor = ConvoyUtils.colorForClientId(cid),
                                latitude = json.optDouble("latitude"),
                                longitude = json.optDouble("longitude"),
                                speed = if (json.has("speed")) json.optDouble("speed") else null,
                                heading = if (json.has("heading")) json.optDouble("heading") else null,
                                accuracy = if (json.has("accuracy")) json.optDouble("accuracy") else null,
                                lastLocationAt = json.optLong("timestamp", System.currentTimeMillis()),
                                lastSeenAt = System.currentTimeMillis(),
                                connectionStatus = MemberConnectionStatus.CONNECTED
                            )
                            current + (cid to newMember)
                        }
                    }
                }
            }
            "destination_update" -> {
                val dest = CaravanProtocol.parseDestination(json)
                if (dest != null) {
                    val current = _tripState.value.destination
                    if (current != null && dest.updatedAt < current.updatedAt) return
                    val ownerId = dest.updatedById.ifBlank { _userProfile.value.clientId }
                    setDestinationInternal(
                        dest.copy(colorHex = ownerColor(ownerId, dest.colorHex)),
                        broadcastRouteClear = true
                    )
                }
            }
            "destination_clear" -> {
                // User rule: Active route/nav must not be automatically deleted by other peers' actions
                val hasActiveRoute = _tripState.value.isNavigating || _tripState.value.route != null
                if (!hasActiveRoute) {
                    routeEpoch++
                    _tripState.update {
                        it.copy(
                            destination = null,
                            route = null,
                            routingProvider = null,
                            isCalculatingRoute = false,
                            isNavigating = false
                        )
                    }
                    abandonOwnPublishedRoute(broadcast = false)
                }
            }
            "chat_message" -> {
                val cid = json.optString("clientId", "")
                // Prevent duplicate message: local user already appended their sent message to local chat state
                if (cid.isNotEmpty() && cid == _userProfile.value.clientId) {
                    return
                }

                val senderName = json.optString("senderName", json.optString("displayName", "Caravan Member"))
                val senderColor = json.optString("senderColor", json.optString("avatarColor", ConvoyUtils.colorForClientId(cid)))
                val text = json.optString("text", "")
                val ts = json.optLong("timestamp", System.currentTimeMillis())

                val chat = ChatMessage(
                    clientId = cid,
                    displayName = senderName,
                    text = text,
                    timestamp = ts,
                    avatarColor = senderColor
                )

                val preview = ChatPreviewItem(
                    senderName = senderName,
                    text = text,
                    avatarColor = senderColor,
                    timestamp = ts
                )

                _tripState.update { state ->
                    val updatedPreviews = (state.visiblePreviews + preview).takeLast(4)
                    state.copy(
                        chatMessages = state.chatMessages + chat,
                        visiblePreviews = updatedPreviews
                    )
                }

                if (cid != _userProfile.value.clientId) {
                    playIncomingMessageSound()
                }

                viewModelScope.launch {
                    delay(3200L)
                    _tripState.update { state ->
                        state.copy(visiblePreviews = state.visiblePreviews.filterNot { it.id == preview.id })
                    }
                }
            }
            "audio_chunk" -> {
                val cid = json.optString("clientId", "")
                // Only play audio from other members
                if (cid.isNotEmpty() && cid != _userProfile.value.clientId) {
                    val speaker = remoteMembers.value[cid]?.displayName
                        ?: json.optString("displayName", "A Driver")
                    pttController.noteRemoteSpeaking(speaker, cid)
                    val dataB64 = json.optString("data", "")
                    if (dataB64.isNotEmpty()) {
                        // Decode + write AudioTrack off Main so map/UI work can't stall the stream.
                        viewModelScope.launch(audioPlaybackDispatcher) {
                            try {
                                val bytes = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                                val sampleRate = json.optInt("sampleRate", 16000)
                                pttController.playAudioChunk(bytes, sampleRate, cid)
                            } catch (e: Exception) {
                                android.util.Log.e("CaravanVM", "Error decoding audio chunk", e)
                            }
                        }
                    }
                }
            }
            "error" -> {
                val code = json.optString("code", "")
                val message = json.optString("message", "")
                android.util.Log.e("CaravanVM", "Server protocol error: $code — $message")
            }
            "ptt_granted" -> {
                val cid = json.optString("clientId", "")
                if (cid == _userProfile.value.clientId) {
                    pttController.onFloorGranted()
                } else {
                    val speaker = remoteMembers.value[cid]?.displayName
                        ?: json.optString("displayName", "A Driver")
                    pttController.onRemoteSpeaking(speaker, cid)
                }
            }
            "ptt_busy" -> {
                // Legacy exclusive-floor signal — treat as remote speaking, do not lock mic.
                val speakerId = json.optString("activeSpeakerId", "")
                val speaker = remoteMembers.value[speakerId]?.displayName ?: "A Driver"
                pttController.onRemoteSpeaking(speaker, speakerId.ifEmpty { null })
            }
            "ptt_release" -> {
                val cid = json.optString("clientId", "")
                val selfId = _userProfile.value.clientId
                if (cid.isEmpty() || cid == selfId) {
                    pttController.onFloorReleased()
                } else {
                    val speaker = remoteMembers.value[cid]?.displayName
                    pttController.onRemoteStopped(speaker, cid)
                }
            }
            "kicked" -> {
                val reason = json.optString("reason", "You were removed from the trip by the leader.")
                viewModelScope.launch(Dispatchers.Main) {
                    leaveTrip()
                    _tripState.update { it.copy(kickedReason = reason) }
                }
            }
        }
    }

    private fun abandonOwnPublishedRoute(broadcast: Boolean) {
        val selfId = _userProfile.value.clientId
        val had =
            _tripState.value.sharedRoutes.containsKey(selfId) || _tripState.value.route != null
        _tripState.update {
            it.copy(
                route = null,
                routingProvider = null,
                isCalculatingRoute = false,
                isNavigating = false,
                sharedRoutes = it.sharedRoutes - selfId
            )
        }
        if (broadcast && had) {
            try {
                wsClient.send(CaravanProtocol.buildRouteClear())
            } catch (_: Exception) {
            }
        }
    }

    private fun setDestinationInternal(
        dest: TripDestination,
        broadcastRouteClear: Boolean = false
    ) {
        val cur = _tripState.value.destination
        val hasRoute = _tripState.value.route != null
        val isSameDest = cur != null &&
            kotlin.math.abs(cur.latitude - dest.latitude) < 0.0001 &&
            kotlin.math.abs(cur.longitude - dest.longitude) < 0.0001
        if (hasRoute && (isSameDest || _tripState.value.isNavigating)) {
            _tripState.update { it.copy(destination = dest) }
            return
        }

        routeEpoch++
        val selfId = _userProfile.value.clientId
        val hadPublished =
            _tripState.value.sharedRoutes.containsKey(selfId) || _tripState.value.route != null
        _tripState.update {
            it.copy(
                destination = dest,
                route = null,
                routingProvider = null,
                isCalculatingRoute = false,
                isNavigating = false,
                sharedRoutes = it.sharedRoutes - selfId
            )
        }
        if (broadcastRouteClear && hadPublished) {
            try {
                wsClient.send(CaravanProtocol.buildRouteClear())
            } catch (_: Exception) {
            }
        }
    }

    fun calculateRouteWithProvider(provider: RoutingProvider) {
        val dest = _tripState.value.destination ?: return
        val epoch = ++routeEpoch
        _tripState.update { it.copy(routingProvider = provider, isCalculatingRoute = true) }

        viewModelScope.launch {
            val userLoc = currentLocation.value
            try {
                val route = if (provider == RoutingProvider.NESHAN) {
                    apiClient.calculateNeshanRoute(
                        startLat = userLoc.latitude,
                        startLng = userLoc.longitude,
                        endLat = dest.latitude,
                        endLng = dest.longitude,
                        apiKey = prefs.neshanApiKey
                    )
                } else {
                    apiClient.calculateRoute(
                        startLat = userLoc.latitude,
                        startLng = userLoc.longitude,
                        endLat = dest.latitude,
                        endLng = dest.longitude
                    )
                }
                if (epoch != routeEpoch) return@launch

                _tripState.update {
                    it.copy(route = route, isCalculatingRoute = false)
                }

                if (route != null && route.points.isNotEmpty()) {
                    val isTraffic = provider == RoutingProvider.NESHAN && route.segments.isNotEmpty()
                    // OSRM → driver's unique color; Neshan → traffic greens/oranges/reds for everyone
                    val routeColorHex = if (isTraffic) {
                        route.segments.first().colorHex
                    } else {
                        _userProfile.value.avatarColor
                    }

                    val selfRoute = SharedRoute(
                        clientId = _userProfile.value.clientId,
                        colorHex = routeColorHex,
                        points = route.points,
                        segments = if (isTraffic) route.segments else emptyList()
                    )
                    _tripState.update { it.copy(sharedRoutes = it.sharedRoutes + (_userProfile.value.clientId to selfRoute)) }

                    try {
                        val routeMsg = CaravanProtocol.buildRouteUpdate(
                            points = route.points,
                            colorHex = routeColorHex,
                            segments = if (isTraffic) route.segments else emptyList()
                        )
                        wsClient.send(routeMsg)
                    } catch (e: Exception) {
                        android.util.Log.e("Caravan", "Failed to broadcast route", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Caravan", "Route calculation failed", e)
                if (epoch == routeEpoch) {
                    _tripState.update { it.copy(isCalculatingRoute = false) }
                }
            }
        }
    }

    fun startNavigation() {
        _tripState.update { it.copy(isNavigating = true) }
    }

    fun exitDrivingMode() {
        _tripState.update { it.copy(isNavigating = false) }
    }

    fun stopNavigation() {
        routeEpoch++
        val selfId = _userProfile.value.clientId
        val state = _tripState.value
        val token = state.leaderToken
        val clearSharedDest = state.isLeader && !token.isNullOrBlank()
        val ifUpdatedAt = state.destination?.updatedAt

        _tripState.update {
            it.copy(
                isNavigating = false,
                route = null,
                routingProvider = null,
                isCalculatingRoute = false,
                destination = null,
                sharedRoutes = it.sharedRoutes - selfId,
                marks = it.marks - selfId
            )
        }
        try {
            wsClient.send(CaravanProtocol.buildRouteClear())
            wsClient.send(CaravanProtocol.buildMapMarkClear())
            if (clearSharedDest && token != null) {
                wsClient.send(CaravanProtocol.buildDestinationClear(token, ifUpdatedAt))
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    fun launchNeshanOrExternalNav(context: android.content.Context) {
        val dest = _tripState.value.destination ?: return
        val lat = dest.latitude
        val lng = dest.longitude
        val label = dest.label ?: "Destination"

        // 1. Try launching Neshan App via nshn scheme
        try {
            val neshanIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("nshn:route?destination=$lat,$lng")
            ).apply {
                setPackage("org.neshan.maps.and.navi")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(neshanIntent)
            return
        } catch (_: Exception) {
            // ignore and try next
        }

        // 2. Try launching Neshan App via geo URI targeted to Neshan package
        try {
            val neshanGeoIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(${android.net.Uri.encode(label)})")
            ).apply {
                setPackage("org.neshan.maps.and.navi")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(neshanGeoIntent)
            return
        } catch (_: Exception) {
            // ignore and try next
        }

        // 3. Fallback to generic geo intent (works with Google Maps, Balad, Waze, etc.)
        try {
            val geoIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("geo:$lat,$lng?q=$lat,$lng(${android.net.Uri.encode(label)})")
            ).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(geoIntent)
        } catch (_: Exception) {
            // 4. If no map app, try opening Neshan in Cafe Bazaar or Play Store
            try {
                val bazaarIntent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("bazaar://details?id=org.neshan.maps.and.navi")
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(bazaarIntent)
            } catch (_: Exception) {
                try {
                    val marketIntent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=org.neshan.maps.and.navi")
                    ).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                } catch (_: Exception) {
                    android.widget.Toast.makeText(context, "No map application found", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun setDestination(latitude: Double, longitude: Double, label: String) {
        if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) {
            return
        }
        val state = _tripState.value
        val token = state.leaderToken
        if (!state.isLeader || token.isNullOrBlank()) {
            android.util.Log.w("Caravan", "Only the leader can set destination")
            return
        }
        val safeLat = latitude.coerceIn(-85.0, 85.0)
        val safeLng = longitude.coerceIn(-180.0, 180.0)
        val self = _userProfile.value
        val userColor = self.avatarColor
        val ifUpdatedAt = state.destination?.updatedAt

        val dest = TripDestination(
            latitude = safeLat,
            longitude = safeLng,
            label = label,
            updatedAt = System.currentTimeMillis(),
            updatedById = self.clientId,
            updatedByName = self.displayName,
            colorHex = userColor
        )
        setDestinationInternal(dest, broadcastRouteClear = true)

        val selfMark = MapMark(
            clientId = self.clientId,
            displayName = self.displayName,
            latitude = safeLat,
            longitude = safeLng,
            color = userColor
        )
        _tripState.update { it.copy(marks = it.marks + (self.clientId to selfMark)) }

        try {
            val msg = CaravanProtocol.buildDestinationUpdate(
                latitude = safeLat,
                longitude = safeLng,
                leaderToken = token,
                label = label,
                colorHex = userColor,
                ifUpdatedAt = ifUpdatedAt
            )
            wsClient.send(msg)

            val markMsg = CaravanProtocol.buildMapMark(
                latitude = safeLat,
                longitude = safeLng,
                color = userColor
            )
            wsClient.send(markMsg)
        } catch (e: Exception) {
            android.util.Log.e("Caravan", "Failed to send destination update", e)
        }
    }

    /** Long-press pin: personal mark + open nav HUD — does not publish shared DEST. */
    fun placeMapMark(latitude: Double, longitude: Double) {
        if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) {
            return
        }
        val safeLat = latitude.coerceIn(-85.0, 85.0)
        val safeLng = longitude.coerceIn(-180.0, 180.0)
        val self = _userProfile.value
        val userColor = self.avatarColor
        val selfMark = MapMark(
            clientId = self.clientId,
            displayName = self.displayName,
            latitude = safeLat,
            longitude = safeLng,
            color = userColor
        )
        _tripState.update { it.copy(marks = it.marks + (self.clientId to selfMark)) }
        // Open pre-drive nav panel (Regular / Neshan) without auto-starting driving mode.
        setDestinationInternal(
            TripDestination(
                latitude = safeLat,
                longitude = safeLng,
                label = "Marked Point",
                updatedAt = System.currentTimeMillis(),
                updatedById = self.clientId,
                updatedByName = self.displayName,
                colorHex = userColor
            ),
            broadcastRouteClear = false
        )
        try {
            wsClient.send(
                CaravanProtocol.buildMapMark(
                    latitude = safeLat,
                    longitude = safeLng,
                    color = userColor
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("Caravan", "Failed to send map mark", e)
        }
    }

    fun followSharedRoute(clientId: String) {
        val shared = _tripState.value.sharedRoutes[clientId] ?: return
        if (shared.points.size < 2) return

        val member = _tripState.value.members.find { it.id == clientId }
        val memberName = member?.displayName ?: "Convoy Member"
        val lastPoint = shared.points.last()

        val dest = TripDestination(
            latitude = lastPoint.latitude,
            longitude = lastPoint.longitude,
            label = "$memberName's Path",
            updatedAt = System.currentTimeMillis(),
            updatedById = clientId,
            updatedByName = memberName,
            colorHex = member?.avatarColor ?: shared.colorHex
        )

        val totalDist = ConvoyUtils.calculateRouteDistanceMeters(shared.points)
        val durationSec = (totalDist / 15.0).coerceAtLeast(60.0)

        val adoptedRoute = RouteResult(
            points = shared.points,
            distanceMeters = totalDist,
            durationSeconds = durationSec,
            segments = shared.segments
        )

        _tripState.update {
            it.copy(
                destination = dest,
                route = adoptedRoute,
                isNavigating = true
            )
        }
    }

    fun navigateToMemberMark(mark: MapMark) {
        // Personal nav target only — do not publish as shared convoy DEST
        setDestinationInternal(
            TripDestination(
                latitude = mark.latitude,
                longitude = mark.longitude,
                label = "${mark.displayName}'s Mark",
                updatedAt = System.currentTimeMillis(),
                updatedById = mark.clientId,
                updatedByName = mark.displayName,
                colorHex = mark.color
            ),
            broadcastRouteClear = false
        )
        calculateRouteWithProvider(RoutingProvider.OSRM)
        startNavigation()
    }

    fun sendChatMessage(text: String) {
        if (text.isBlank()) return
        val trimmed = text.trim()
        val msg = CaravanProtocol.buildChatMessage(trimmed)
        wsClient.send(msg)

        val self = _userProfile.value
        val localChat = ChatMessage(
            clientId = self.clientId,
            displayName = self.displayName,
            text = trimmed,
            avatarColor = self.avatarColor
        )
        val preview = ChatPreviewItem(
            senderName = self.displayName,
            text = trimmed,
            avatarColor = self.avatarColor
        )
        _tripState.update { state ->
            val updatedPreviews = (state.visiblePreviews + preview).takeLast(4)
            state.copy(
                chatMessages = state.chatMessages + localChat,
                visiblePreviews = updatedPreviews
            )
        }
        viewModelScope.launch {
            delay(3200L)
            _tripState.update { state ->
                state.copy(visiblePreviews = state.visiblePreviews.filterNot { it.id == preview.id })
            }
        }
    }

    fun sendQuickPrompt(prompt: String) {
        sendChatMessage(prompt)
        pttController.playChirpStart()
    }

    fun togglePtt() {
        if (_tripState.value.voiceNoteRecording) return
        if (_tripState.value.pttState == PttState.TRANSMITTING || _tripState.value.pttState == PttState.REQUESTING) {
            releasePtt()
        } else {
            startPtt()
        }
    }

    fun startPtt() {
        if (_tripState.value.voiceNoteRecording) return
        val state = _tripState.value.pttState
        if (state == PttState.TRANSMITTING || state == PttState.REQUESTING) return
        // Instant talk — do not wait for server grant / queue.
        pttController.onFloorGranted()
        // Notify peers for "X is talking" UI only.
        wsClient.send(CaravanProtocol.buildPttRequest())
    }

    fun releasePtt() {
        pttController.onFloorReleased()
    }

    fun toggleVoiceNote() {
        if (_tripState.value.voiceNoteReady) {
            sendVoiceNote()
        } else if (_tripState.value.voiceNoteRecording) {
            finishVoiceNoteRecording()
        } else {
            startVoiceNote()
        }
    }

    fun startVoiceNote() {
        val ptt = _tripState.value.pttState
        if (ptt == PttState.TRANSMITTING || ptt == PttState.REQUESTING) return
        if (_tripState.value.voiceNoteRecording) return
        pttController.startVoiceNote()
    }

    fun releaseVoiceNote() {
        finishVoiceNoteRecording()
    }

    fun finishVoiceNoteRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            val pcm = pttController.stopVoiceNote()
            if (pcm.isEmpty()) {
                android.util.Log.w("CaravanVM", "Voice note empty — not sent")
                withContext(Dispatchers.Main) {
                    _tripState.update { it.copy(voiceNoteReady = false) }
                }
                return@launch
            }
            pendingVoiceNote = pcm
            withContext(Dispatchers.Main) {
                _tripState.update { it.copy(voiceNoteReady = true) }
            }
        }
    }

    fun cancelVoiceNote() {
        pendingVoiceNote = null
        _tripState.update { it.copy(voiceNoteReady = false) }
    }

    fun sendVoiceNote() {
        val pcm = pendingVoiceNote ?: return
        pendingVoiceNote = null
        _tripState.update { it.copy(voiceNoteReady = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val totalBytes = pcm.size
            val audioDurationMs = (totalBytes * 1000L) / (16000L * 2L)
            android.util.Log.d("CaravanVM", "Sending voice note $totalBytes bytes (~${audioDurationMs}ms)")
            withContext(Dispatchers.Main) {
                sendChatMessage("🎤 Voice note")
            }
            // Signal peer PTT state so UI speaking indicator displays
            wsClient.send(CaravanProtocol.buildPttRequest())

            val startTime = System.currentTimeMillis()
            var seq = 0
            var offset = 0
            val chunkSize = 4096
            while (offset < totalBytes) {
                val end = minOf(offset + chunkSize, totalBytes)
                val slice = pcm.copyOfRange(offset, end)
                val b64 = android.util.Base64.encodeToString(slice, android.util.Base64.NO_WRAP)
                if (!wsClient.send(CaravanProtocol.buildAudioChunk(b64, 16000, seq++))) {
                    android.util.Log.w("CaravanVM", "voice note chunk send failed seq=${seq - 1}")
                    delay(30L)
                    wsClient.send(CaravanProtocol.buildAudioChunk(b64, 16000, seq - 1))
                }
                offset = end
                // Smooth pacing that prevents network saturation while buffering quickly
                delay(25L)
            }

            // Keep speaking indicator visible for the audio playback duration
            val elapsed = System.currentTimeMillis() - startTime
            val remaining = audioDurationMs - elapsed
            if (remaining > 0L) {
                delay(minOf(remaining, 30_000L))
            }
            wsClient.send(CaravanProtocol.buildPttRelease())
        }
    }

    fun leaveTrip() {
        com.example.service.CaravanTripForegroundService.stop(getApplication())
        locationSyncJob?.cancel()
        locationProvider.stopMockFleet()
        locationProvider.stopLocationUpdates()
        _isMyLocationActive.value = false
        wsClient.send(CaravanProtocol.buildLeave())
        wsClient.disconnect()
        _tripState.value = TripUiState()
        // Saved trips stay until the user deletes them.
    }

    fun kickMember(targetClientId: String) {
        if (!_tripState.value.isLeader) return
        val token = _tripState.value.leaderToken
        val msg = CaravanProtocol.buildKickMember(targetClientId, token)
        try {
            wsClient.send(msg)
        } catch (_: Exception) {}
        remoteMembers.update { it - targetClientId }
        _tripState.update { s ->
            s.copy(
                members = s.members.filterNot { it.id == targetClientId },
                marks = s.marks - targetClientId,
                sharedRoutes = s.sharedRoutes - targetClientId
            )
        }
    }

    fun clearKickedReason() {
        _tripState.update { it.copy(kickedReason = null) }
    }

    fun deleteSavedTrip(tripId: String) {
        if (_tripState.value.tripId == tripId) {
            leaveTrip()
        }
        prefs.removeTrip(tripId)
        _savedTrips.value = prefs.getSavedTrips()
    }

    private fun playIncomingMessageSound() {
        if (!prefs.isMessageSoundEnabled) return
        try {
            val uri = android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_NOTIFICATION
            )
            val ringtone = android.media.RingtoneManager.getRingtone(getApplication(), uri)
            ringtone?.play()
        } catch (_: Exception) {
            try {
                pttController.playFloorAlert()
            } catch (_: Exception) {}
        }
    }

    fun toggleMockFleet(enabled: Boolean) {
        prefs.isMockFleetEnabled = enabled
        if (enabled && _tripState.value.inTrip) {
            locationProvider.startMockFleet(viewModelScope)
        } else {
            locationProvider.stopMockFleet()
        }
    }

    /** Unique color for a convoy member — GPS / DEST / route all share this. */
    private fun ownerColor(clientId: String, fallback: String? = null): String {
        val selfId = _userProfile.value.clientId
        if (clientId.isBlank() || clientId == selfId) {
            return _userProfile.value.avatarColor
        }
        return _tripState.value.members.find { it.id == clientId }?.avatarColor
            ?: remoteMembers.value[clientId]?.avatarColor
            ?: fallback?.takeIf { ConvoyUtils.normalizeHex(it).length == 7 }
            ?: ConvoyUtils.colorForClientId(clientId)
    }

    fun isGpsEnabled(): Boolean = locationProvider.isGpsEnabled()
    fun openLocationSettings() = locationProvider.openLocationSettings()
    fun requestImmediateLocation() = locationProvider.requestImmediateLocation()

    /** After WS join succeeds, register FCM token so offline peers can be notified. */
    private fun registerPushTokenAfterJoin() {
        viewModelScope.launch(Dispatchers.IO) {
            val token = PushRegistrar.fetchToken(getApplication()) ?: return@launch
            withContext(Dispatchers.Main) {
                wsClient.send(CaravanProtocol.buildRegisterPush(token))
            }
        }
    }

    /**
     * When GPS or the socket goes quiet, keep the last pin and flip connection status
     * so markers/fleet UI can show "Xm ago" / Offline.
     */
    private fun refreshMemberPresence() {
        val now = System.currentTimeMillis()
        remoteMembers.update { current ->
            if (current.isEmpty()) return@update current
            var changed = false
            val next = current.mapValues { (_, member) ->
                // Server-marked offline stays offline until a fresh location_update.
                if (member.connectionStatus == MemberConnectionStatus.OFFLINE) {
                    return@mapValues member
                }
                val locTime = maxOf(member.lastLocationAt ?: 0L, member.lastSeenAt)
                val age = (now - locTime).coerceAtLeast(0L)
                val inferred = when {
                    age >= OFFLINE_AFTER_MS -> MemberConnectionStatus.OFFLINE
                    age >= RECONNECTING_AFTER_MS -> MemberConnectionStatus.RECONNECTING
                    else -> MemberConnectionStatus.CONNECTED
                }
                if (inferred != member.connectionStatus) {
                    changed = true
                    member.copy(connectionStatus = inferred)
                } else {
                    member
                }
            }
            if (changed) next else current
        }
    }

    val updateManager = AppUpdateManager(application)
    private val _updateState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val updateState: StateFlow<UpdateDownloadState> = _updateState.asStateFlow()

    fun checkForAppUpdates() {
        if (_updateState.value is UpdateDownloadState.Checking || _updateState.value is UpdateDownloadState.Downloading) {
            return
        }
        viewModelScope.launch {
            _updateState.value = UpdateDownloadState.Checking
            val info = updateManager.checkForUpdates(prefs.apiBaseUrl)
            if (info != null) {
                _updateState.value = UpdateDownloadState.Available(info)
            } else {
                _updateState.value = UpdateDownloadState.UpToDate
            }
        }
    }

    fun downloadAndInstallUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            try {
                val apkFile = updateManager.downloadAndPrepareApk(info) { percent, isPatch, status ->
                    _updateState.value = UpdateDownloadState.Downloading(percent, isPatch, status)
                }
                _updateState.value = UpdateDownloadState.ReadyToInstall(apkFile)
                updateManager.promptInstall(apkFile)
            } catch (e: Exception) {
                _updateState.value = UpdateDownloadState.Error(e.message ?: "Update download failed")
            }
        }
    }

    fun dismissUpdateState() {
        _updateState.value = UpdateDownloadState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        // If the user is actively in a convoy trip, preserve the Foreground Service,
        // live GPS tracking, and connection across Android Activity recreation or backgrounding.
        if (!_tripState.value.inTrip) {
            com.example.service.CaravanTripForegroundService.onLeaveRequested = null
            com.example.service.CaravanTripForegroundService.stop(getApplication())
            locationProvider.stopLocationUpdates()
            pttController.release()
            wsClient.disconnect()
        }
    }
}

/**
 * Prefer incoming snapshot fields, but never drop a known lat/lng when the server
 * omits coordinates for an offline member.
 */
private fun mergeMembersPreservingLocation(
    previous: Map<String, TripMember>,
    incoming: Map<String, TripMember>
): Map<String, TripMember> {
    if (incoming.isEmpty()) return incoming
    return incoming.mapValues { (id, member) ->
        val prev = previous[id] ?: return@mapValues member
        val hasIncomingLoc = member.latitude != null && member.longitude != null &&
            member.latitude != 0.0 && member.longitude != 0.0
        if (hasIncomingLoc) {
            member
        } else if (prev.latitude != null && prev.longitude != null) {
            member.copy(
                latitude = prev.latitude,
                longitude = prev.longitude,
                accuracy = member.accuracy ?: prev.accuracy,
                heading = member.heading ?: prev.heading,
                lastLocationAt = member.lastLocationAt ?: prev.lastLocationAt
            )
        } else {
            member
        }
    }
}

// Extension copyWith on TripMember
private fun TripMember.copyWith(
    displayName: String? = null,
    carName: String? = null,
    memberKind: MemberKind? = null,
    avatarColor: String? = null,
    latitude: Double? = null,
    longitude: Double? = null,
    accuracy: Double? = null,
    speed: Double? = null,
    heading: Double? = null,
    lastLocationAt: Long? = null,
    lastSeenAt: Long? = null,
    connectionStatus: MemberConnectionStatus? = null,
    isLeader: Boolean? = null
) = TripMember(
    id = id,
    displayName = displayName ?: this.displayName,
    carName = carName ?: this.carName,
    memberKind = memberKind ?: this.memberKind,
    avatarColor = avatarColor ?: this.avatarColor,
    latitude = latitude ?: this.latitude,
    longitude = longitude ?: this.longitude,
    accuracy = accuracy ?: this.accuracy,
    speed = speed ?: this.speed,
    heading = heading ?: this.heading,
    lastLocationAt = lastLocationAt ?: this.lastLocationAt,
    lastSeenAt = lastSeenAt ?: this.lastSeenAt,
    connectionStatus = connectionStatus ?: this.connectionStatus,
    isLeader = isLeader ?: this.isLeader
)
