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
import com.example.util.ConvoyUtils
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

data class TripUiState(
    val inTrip: Boolean = false,
    val tripId: String = "",
    val inviteCode: String = "",
    val tripName: String = "Caravan Trip",
    val isLeader: Boolean = false,
    val leaderToken: String? = null,
    val connectionStatus: CaravanConnectionStatus = CaravanConnectionStatus.DISCONNECTED,
    val connectionError: String? = null,
    val members: List<TripMember> = emptyList(),
    val destination: TripDestination? = null,
    val route: RouteResult? = null,
    val routingProvider: RoutingProvider = RoutingProvider.OSRM,
    val isCalculatingRoute: Boolean = false,
    val isNavigating: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(),
    val latestAlert: ChatMessage? = null,
    val visiblePreviews: List<ChatPreviewItem> = emptyList(),
    val sharedRoutes: Map<String, SharedRoute> = emptyMap(),
    val marks: Map<String, MapMark> = emptyMap(),
    val activeSpeakerName: String? = null,
    val pttState: PttState = PttState.IDLE,
    val audioAmplitude: Float = 0f
)

class CaravanViewModel(application: Application) : AndroidViewModel(application) {
    val prefs = PreferencesManager(application)
    val apiClient = CaravanApiClient(prefs.apiBaseUrl)
    val wsClient = CaravanWebSocketClient(prefs.wsBaseUrl)
    val locationProvider = LocationProvider(application)
    val pttController = VoicePttController(application)

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

    private val _userProfile = MutableStateFlow(prefs.getUserProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _savedTrip = MutableStateFlow(prefs.getSavedTrip())
    val savedTrip: StateFlow<SavedTrip?> = _savedTrip.asStateFlow()

    private val _tripState = MutableStateFlow(TripUiState())
    val tripState: StateFlow<TripUiState> = _tripState.asStateFlow()

    val currentLocation: StateFlow<DeviceLocation> = locationProvider.currentLocation

    private val remoteMembers = MutableStateFlow<Map<String, TripMember>>(emptyMap())
    private var locationSyncJob: Job? = null

    init {
        // Listen to WebSocket events
        viewModelScope.launch {
            wsClient.events.collect { event ->
                handleWsEvent(event)
            }
        }

        // Hook up PTT audio streaming to broadcast live voice chunks over WebSocket
        var audioSeq = 0
        pttController.onAudioChunkCaptured = { pcmBytes ->
            if (_tripState.value.pttState == PttState.TRANSMITTING) {
                try {
                    val b64 = android.util.Base64.encodeToString(pcmBytes, android.util.Base64.NO_WRAP)
                    val msg = CaravanProtocol.buildAudioChunk(b64, 16000, audioSeq++)
                    wsClient.send(msg)
                } catch (e: Exception) {
                    android.util.Log.e("CaravanVM", "Failed to stream audio chunk", e)
                }
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
                val (resolvedSelfColor, coloredMembers) = ConvoyUtils.resolveDistinctColors(
                    selfId = _userProfile.value.clientId,
                    selfPreferredColor = _userProfile.value.avatarColor,
                    members = memberList
                )
                if (resolvedSelfColor != _userProfile.value.avatarColor) {
                    val updatedProfile = _userProfile.value.copy(avatarColor = resolvedSelfColor)
                    _userProfile.value = updatedProfile
                    prefs.saveUserProfile(updatedProfile)
                }
                _tripState.update { it.copy(members = coloredMembers) }
            }
        }

        // Forward PTT states and audio amplitude
        viewModelScope.launch {
            combine(
                pttController.pttState,
                pttController.activeSpeakerName,
                pttController.audioAmplitude
            ) { state, speaker, amplitude ->
                Triple(state, speaker, amplitude)
            }.collect { (state, speaker, amplitude) ->
                _tripState.update {
                    it.copy(
                        pttState = state,
                        activeSpeakerName = speaker,
                        audioAmplitude = amplitude
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
                                android.util.Log.i("Caravan", "Off-route detected (${distToRoute.toInt()}m). Auto-updating route using ${state.routingProvider}...")
                                calculateRouteWithProvider(state.routingProvider)
                            }
                        } else {
                            consecutiveDeviationCount = 0
                        }
                    }
                }
            }
        }

        locationProvider.startLocationUpdates()
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

    fun refreshGps() {
        locationProvider.startLocationUpdates()
    }

    fun updateProfile(displayName: String, carName: String, avatarColor: String) {
        val updated = _userProfile.value.copy(
            displayName = displayName.trim().ifEmpty { "Driver" },
            carName = carName.trim().ifEmpty { "SUV" },
            avatarColor = avatarColor
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
                prefs.saveTrip(saved)
                _savedTrip.value = saved

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
        val input = codeOrUrl.trim()
        val inviteCode = if (input.contains("code=")) {
            input.substringAfter("code=").substringBefore("&")
        } else {
            input
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
                    tripName = "Caravan Convoy",
                    isLeader = false
                )
                prefs.saveTrip(saved)
                _savedTrip.value = saved

                _tripState.update {
                    it.copy(
                        tripId = tripId,
                        inviteCode = inviteCode,
                        tripName = "Caravan Convoy",
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

    fun rejoinSavedTrip() {
        val trip = _savedTrip.value ?: return
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
        connectToTrip(trip.tripId, trip.inviteCode, trip.leaderToken)
    }

    private fun connectToTrip(tripId: String, inviteCode: String, leaderToken: String?) {
        wsClient.updateWsBaseUrl(prefs.wsBaseUrl)
        wsClient.connect(tripId, viewModelScope)

        if (prefs.isMockFleetEnabled) {
            locationProvider.startMockFleet(viewModelScope)
        }

        // Start sending location updates periodically
        startLocationBroadcaster()
    }

    private fun startLocationBroadcaster() {
        locationSyncJob?.cancel()
        locationSyncJob = viewModelScope.launch {
            while (isActive) {
                delay(2000L)
                val loc = currentLocation.value
                if (loc.isRealGps && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
                    val msg = CaravanProtocol.buildLocationUpdate(
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                        accuracy = loc.accuracy,
                        speed = loc.speed,
                        heading = loc.heading
                    )
                    wsClient.send(msg)
                }
            }
        }
    }

    private fun handleWsEvent(event: CaravanWsEvent) {
        when (event) {
            is CaravanWsEvent.Connected -> {
                _tripState.update { it.copy(connectionStatus = CaravanConnectionStatus.CONNECTED, connectionError = null) }
                // Immediately send join packet over the live connection
                val invite = _tripState.value.inviteCode.ifEmpty { _savedTrip.value?.inviteCode ?: "" }
                val token = _tripState.value.leaderToken ?: _savedTrip.value?.leaderToken
                val joinMsg = CaravanProtocol.buildJoin(
                    clientId = _userProfile.value.clientId,
                    displayName = _userProfile.value.displayName,
                    inviteCode = invite,
                    carName = _userProfile.value.carName,
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
                _tripState.update { it.copy(connectionStatus = CaravanConnectionStatus.RECONNECTING) }
            }
            is CaravanWsEvent.Disconnected -> {
                _tripState.update { it.copy(connectionStatus = CaravanConnectionStatus.DISCONNECTED) }
            }
            is CaravanWsEvent.Error -> {
                if (event.isNotFound) {
                    prefs.clearSavedTrip()
                    _savedTrip.value = null
                    _tripState.update {
                        it.copy(
                            connectionStatus = CaravanConnectionStatus.ERROR,
                            connectionError = "Trip not found on server or has expired. Please create or join a new trip."
                        )
                    }
                } else {
                    _tripState.update { it.copy(connectionStatus = CaravanConnectionStatus.RECONNECTING) }
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
                val membersArr = json.optJSONArray("members")
                if (membersArr != null) {
                    val list = CaravanProtocol.parseMembersList(membersArr, leaderId)
                    val map = list.associateBy { it.id }.toMutableMap()
                    remoteMembers.value = map
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
                        val markMap = mutableMapOf<String, MapMark>()
                        for (i in 0 until marksArr.length()) {
                            val mObj = marksArr.optJSONObject(i)
                            if (mObj != null) {
                                val m = CaravanProtocol.parseMapMark(mObj)
                                if (m != null && m.clientId.isNotEmpty()) markMap[m.clientId] = m
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
                                if (r.clientId.isNotEmpty() && r.points.isNotEmpty()) routeMap[r.clientId] = r
                            }
                        }
                        _tripState.update { it.copy(sharedRoutes = routeMap) }
                    }
                }
            }
            "member_joined" -> {
                val mJson = json.optJSONObject("member") ?: json
                val member = CaravanProtocol.parseMember(mJson)
                remoteMembers.update { it + (member.id to member) }
            }
            "member_left" -> {
                val cid = json.optString("clientId", "")
                if (cid.isNotEmpty()) {
                    remoteMembers.update { it - cid }
                    _tripState.update { it.copy(marks = it.marks - cid, sharedRoutes = it.sharedRoutes - cid) }
                }
            }
            "members_snapshot" -> {
                val membersArr = json.optJSONArray("members")
                if (membersArr != null) {
                    val list = CaravanProtocol.parseMembersList(membersArr, null)
                    remoteMembers.value = list.associateBy { it.id }
                }
            }
            "map_mark" -> {
                val mObj = json.optJSONObject("mark") ?: json
                val mark = CaravanProtocol.parseMapMark(mObj)
                if (mark != null && mark.clientId.isNotEmpty()) {
                    _tripState.update { it.copy(marks = it.marks + (mark.clientId to mark)) }
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
                    _tripState.update { it.copy(sharedRoutes = it.sharedRoutes + (shared.clientId to shared)) }
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
                    setDestinationInternal(dest)
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
                    pttController.playFloorAlert()
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
                    val dataB64 = json.optString("data", "")
                    if (dataB64.isNotEmpty()) {
                        try {
                            val bytes = android.util.Base64.decode(dataB64, android.util.Base64.DEFAULT)
                            val sampleRate = json.optInt("sampleRate", 16000)
                            pttController.playAudioChunk(bytes, sampleRate)
                        } catch (e: Exception) {
                            android.util.Log.e("CaravanVM", "Error decoding audio chunk", e)
                        }
                    }
                }
            }
            "ptt_granted" -> {
                val cid = json.optString("clientId", "")
                if (cid == _userProfile.value.clientId) {
                    pttController.onFloorGranted()
                } else {
                    val speaker = remoteMembers.value[cid]?.displayName ?: "A Driver"
                    pttController.onFloorBusy(speaker)
                }
            }
            "ptt_busy" -> {
                val speakerId = json.optString("activeSpeakerId", "")
                val speaker = remoteMembers.value[speakerId]?.displayName ?: "A Driver"
                pttController.onFloorBusy(speaker)
            }
            "ptt_release" -> {
                pttController.onFloorReleased()
            }
        }
    }

    private fun setDestinationInternal(dest: TripDestination) {
        _tripState.update { it.copy(destination = dest) }
        // Note: Do not auto-calculate route here to preserve tokens
    }

    fun calculateRouteWithProvider(provider: RoutingProvider) {
        val dest = _tripState.value.destination ?: return
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
                _tripState.update {
                    it.copy(route = route, isCalculatingRoute = false)
                }

                if (route != null && route.points.isNotEmpty()) {
                    // "بجز وقتی از مسیر نشان استفاده میکنه که باید رنگ مسیر فقط رنگ ترافیک باشه ولی ایکن خودش و مارک تارگتش باید همون رنگ یوزر باشه."
                    val routeColorHex = if (provider == RoutingProvider.NESHAN) {
                        "#10B981" // Traffic green
                    } else {
                        _userProfile.value.avatarColor // Exactly the user's color
                    }

                    val selfRoute = SharedRoute(
                        clientId = _userProfile.value.clientId,
                        colorHex = routeColorHex,
                        points = route.points
                    )
                    _tripState.update { it.copy(sharedRoutes = it.sharedRoutes + (_userProfile.value.clientId to selfRoute)) }

                    try {
                        val routeMsg = CaravanProtocol.buildRouteUpdate(
                            points = route.points,
                            colorHex = routeColorHex
                        )
                        wsClient.send(routeMsg)
                    } catch (e: Exception) {
                        android.util.Log.e("Caravan", "Failed to broadcast route", e)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("Caravan", "Route calculation failed", e)
                _tripState.update { it.copy(isCalculatingRoute = false) }
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
        val selfId = _userProfile.value.clientId
        _tripState.update {
            it.copy(
                isNavigating = false,
                route = null,
                destination = null,
                sharedRoutes = it.sharedRoutes - selfId,
                marks = it.marks - selfId
            )
        }
        try {
            wsClient.send(CaravanProtocol.buildRouteClear())
            wsClient.send(CaravanProtocol.buildMapMarkClear())
        } catch (e: Exception) {
            // ignore
        }
    }

    fun launchNeshanOrExternalNav(context: android.content.Context) {
        val dest = _tripState.value.destination ?: return
        try {
            // Try launching Neshan App first (uri scheme nshn:route?destination=lat,lng)
            val neshanIntent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("nshn:route?destination=${dest.latitude},${dest.longitude}")
            ).apply {
                setPackage("org.neshan.maps.and.navi")
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(neshanIntent)
        } catch (_: Exception) {
            // Fallback to standard geo intent (works with Google Maps, Balad, Waze, etc.)
            try {
                val geoIntent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("geo:${dest.latitude},${dest.longitude}?q=${dest.latitude},${dest.longitude}(${android.net.Uri.encode(dest.label ?: "Destination")})")
                ).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(geoIntent)
            } catch (_: Exception) {
                android.widget.Toast.makeText(context, "No map application found", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setDestination(latitude: Double, longitude: Double, label: String) {
        if (latitude.isNaN() || longitude.isNaN() || latitude.isInfinite() || longitude.isInfinite()) {
            return
        }
        val safeLat = latitude.coerceIn(-85.0, 85.0)
        val safeLng = longitude.coerceIn(-180.0, 180.0)
        val token = _tripState.value.leaderToken ?: "leader-token"
        val self = _userProfile.value
        val userColor = self.avatarColor

        val dest = TripDestination(
            latitude = safeLat,
            longitude = safeLng,
            label = label,
            updatedAt = System.currentTimeMillis(),
            updatedById = self.clientId,
            updatedByName = self.displayName,
            colorHex = userColor
        )
        setDestinationInternal(dest)

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
                colorHex = userColor
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
            colorHex = shared.colorHex
        )

        val totalDist = ConvoyUtils.calculateRouteDistanceMeters(shared.points)
        val durationSec = (totalDist / 15.0).coerceAtLeast(60.0)

        val adoptedRoute = RouteResult(
            points = shared.points,
            distanceMeters = totalDist,
            durationSeconds = durationSec
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
        setDestination(mark.latitude, mark.longitude, "${mark.displayName}'s Mark")
        calculateRouteWithProvider(_tripState.value.routingProvider)
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
        if (_tripState.value.pttState == PttState.TRANSMITTING || _tripState.value.pttState == PttState.REQUESTING) {
            releasePtt()
        } else {
            startPtt()
        }
    }

    fun startPtt() {
        if (_tripState.value.pttState == PttState.BUSY) return
        pttController.onRequestPtt()
        val msg = CaravanProtocol.buildPttRequest()
        val sent = wsClient.send(msg)
        if (!sent || _tripState.value.connectionStatus != CaravanConnectionStatus.CONNECTED) {
            // Local fallback floor grant if offline
            pttController.onFloorGranted()
        }
    }

    fun releasePtt() {
        pttController.onFloorReleased()
        val msg = CaravanProtocol.buildPttRelease()
        wsClient.send(msg)
    }

    fun leaveTrip() {
        locationSyncJob?.cancel()
        locationProvider.stopMockFleet()
        wsClient.send(CaravanProtocol.buildLeave())
        wsClient.disconnect()
        _tripState.value = TripUiState()
    }

    fun deleteSavedTrip() {
        leaveTrip()
        prefs.clearSavedTrip()
        _savedTrip.value = null
    }

    fun toggleMockFleet(enabled: Boolean) {
        prefs.isMockFleetEnabled = enabled
        if (enabled && _tripState.value.inTrip) {
            locationProvider.startMockFleet(viewModelScope)
        } else {
            locationProvider.stopMockFleet()
        }
    }

    fun isGpsEnabled(): Boolean = locationProvider.isGpsEnabled()
    fun openLocationSettings() = locationProvider.openLocationSettings()
    fun requestImmediateLocation() = locationProvider.requestImmediateLocation()

    override fun onCleared() {
        super.onCleared()
        locationProvider.stopLocationUpdates()
        pttController.release()
        wsClient.disconnect()
    }
}

// Extension copyWith on TripMember
private fun TripMember.copyWith(
    displayName: String? = null,
    carName: String? = null,
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
