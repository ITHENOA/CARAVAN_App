import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/app_config.dart';
import '../../../core/networking/api_client.dart';
import '../../../core/networking/ws_client.dart';
import '../../../core/utils/app_logger.dart';
import '../../../core/utils/convoy_colors.dart';
import '../../members/domain/member.dart';
import '../../onboarding/presentation/profile_provider.dart';
import '../domain/protocol.dart';

class ActiveTripState {
  const ActiveTripState({
    this.tripId,
    this.tripName,
    this.inviteCode,
    this.leaderToken,
    this.leaderId,
    this.clientId,
    this.members = const [],
    this.destination,
    this.marks = const {},
    this.routes = const {},
    this.connection = WsConnectionState.disconnected,
    this.activeSpeakerId,
    this.activeSpeakerName,
    this.lastServerPacketAt,
    this.errorMessage,
    this.joined = false,
    this.chatMessages = const [],
  });

  final String? tripId;
  final String? tripName;
  final String? inviteCode;
  final String? leaderToken;
  final String? leaderId;
  final String? clientId;
  final List<TripMember> members;
  final TripDestination? destination;
  final Map<String, MapMark> marks;
  final Map<String, SharedRoute> routes;
  final WsConnectionState connection;
  final String? activeSpeakerId;
  final String? activeSpeakerName;
  final DateTime? lastServerPacketAt;
  final String? errorMessage;
  final bool joined;
  final List<Map<String, dynamic>> chatMessages;

  bool get isLeader =>
      clientId != null && leaderId != null && clientId == leaderId;

  MapMark? get myMark =>
      clientId == null ? null : marks[clientId!];

  ActiveTripState copyWith({
    String? tripId,
    String? tripName,
    String? inviteCode,
    String? leaderToken,
    String? leaderId,
    String? clientId,
    List<TripMember>? members,
    TripDestination? destination,
    bool clearDestination = false,
    Map<String, MapMark>? marks,
    Map<String, SharedRoute>? routes,
    WsConnectionState? connection,
    String? activeSpeakerId,
    String? activeSpeakerName,
    bool clearSpeaker = false,
    DateTime? lastServerPacketAt,
    String? errorMessage,
    bool clearError = false,
    bool? joined,
    List<Map<String, dynamic>>? chatMessages,
  }) {
    return ActiveTripState(
      tripId: tripId ?? this.tripId,
      tripName: tripName ?? this.tripName,
      inviteCode: inviteCode ?? this.inviteCode,
      leaderToken: leaderToken ?? this.leaderToken,
      leaderId: leaderId ?? this.leaderId,
      clientId: clientId ?? this.clientId,
      members: members ?? this.members,
      destination: clearDestination ? null : (destination ?? this.destination),
      marks: marks ?? this.marks,
      routes: routes ?? this.routes,
      connection: connection ?? this.connection,
      activeSpeakerId: clearSpeaker
          ? null
          : (activeSpeakerId ?? this.activeSpeakerId),
      activeSpeakerName: clearSpeaker
          ? null
          : (activeSpeakerName ?? this.activeSpeakerName),
      lastServerPacketAt: lastServerPacketAt ?? this.lastServerPacketAt,
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
      joined: joined ?? this.joined,
      chatMessages: chatMessages ?? this.chatMessages,
    );
  }
}

class TripController extends Notifier<ActiveTripState> {
  CaravanWsClient? _ws;
  final TripApiClient _api = TripApiClient();
  String? _pendingInvite;
  String? _pendingLeaderToken;

  @override
  ActiveTripState build() {
    ref.onDispose(() {
      unawaited(_ws?.close());
    });
    return const ActiveTripState();
  }

  Future<void> createTrip({String? name}) async {
    final profile = await ref.read(profileProvider.future);
    final tripName = (name != null && name.trim().isNotEmpty)
        ? name.trim()
        : '${profile.displayName} Trip';
    final created = await _api.createTrip(
      displayName: profile.displayName,
      clientId: profile.clientId,
      name: tripName,
    );
    await joinTrip(
      tripId: created.tripId,
      inviteCode: created.inviteCode,
      leaderToken: created.leaderToken,
    );
    state = state.copyWith(
      tripName: created.name,
      inviteCode: created.inviteCode,
      leaderToken: created.leaderToken,
    );
    await ref.read(profileRepositoryProvider).saveLastTrip(
      tripId: created.tripId,
      inviteCode: created.inviteCode,
      tripName: created.name,
      leaderToken: created.leaderToken,
    );
  }

  Future<void> joinTrip({
    required String tripId,
    required String inviteCode,
    String? leaderToken,
  }) async {
    final profile = await ref.read(profileProvider.future);
    _pendingInvite = inviteCode;
    _pendingLeaderToken = leaderToken;
    await _ws?.close();
    final config = AppConfig.current;
    state = state.copyWith(
      tripId: tripId,
      inviteCode: inviteCode,
      leaderToken: leaderToken,
      clientId: profile.clientId,
      joined: false,
      clearError: true,
    );
    _ws = CaravanWsClient(
      urlBuilder: () => '${config.wsBaseUrl}/trip/$tripId',
      onMessage: _onMessage,
      onState: (s) {
        state = state.copyWith(connection: s);
        if (s == WsConnectionState.connected && state.tripId != null) {
          _sendJoin();
        }
      },
    );
    _ws!.connect();
  }

  void _sendJoin() {
    final profile = ref.read(profileProvider).valueOrNull;
    if (profile == null || _pendingInvite == null) return;
    final taken = state.members
        .where((m) => m.id != profile.clientId)
        .map((m) => m.avatarColor ?? '')
        .where((c) => c.isNotEmpty);
    final color = distinctColorFor(
      profile.clientId,
      preferred: profile.avatarColor,
      taken: taken,
    );
    _ws?.send(
      buildJoin(
        clientId: profile.clientId,
        displayName: profile.displayName,
        inviteCode: _pendingInvite!,
        carName: profile.carName,
        avatarColor: color,
        leaderToken: _pendingLeaderToken,
      ),
    );
  }

  void sendLocation({
    required double latitude,
    required double longitude,
    double? accuracy,
    double? speed,
    double? heading,
  }) {
    if (!state.joined) return;
    try {
      _ws?.send(
        buildLocationUpdate(
          latitude: latitude,
          longitude: longitude,
          accuracy: accuracy,
          speed: speed,
          heading: heading,
        ),
      );
    } catch (e, st) {
      AppLogger.d('skip invalid location', e, st);
    }
  }

  void updateDestination({
    required double latitude,
    required double longitude,
    String? label,
  }) {
    final token = state.leaderToken;
    if (token == null) {
      state = state.copyWith(errorMessage: 'Leader only');
      return;
    }
    _ws?.send(
      buildDestinationUpdate(
        latitude: latitude,
        longitude: longitude,
        leaderToken: token,
        label: label,
      ),
    );
  }

  void setMapMark({
    required double latitude,
    required double longitude,
    required String color,
  }) {
    final id = state.clientId;
    final profile = ref.read(profileProvider).valueOrNull;
    if (id != null) {
      final next = Map<String, MapMark>.from(state.marks);
      next[id] = MapMark(
        clientId: id,
        displayName: profile?.displayName ?? 'You',
        latitude: latitude,
        longitude: longitude,
        color: color,
        updatedAt: DateTime.now(),
      );
      state = state.copyWith(marks: next);
    }
    _ws?.send(
      buildMapMark(
        latitude: latitude,
        longitude: longitude,
        color: color,
      ),
    );
  }

  void clearMapMark() {
    final id = state.clientId;
    if (id != null && state.marks.containsKey(id)) {
      final next = Map<String, MapMark>.from(state.marks)..remove(id);
      state = state.copyWith(marks: next);
    }
    _ws?.send(buildMapMarkClear());
  }

  void publishRoute(SharedRoute route) {
    final next = Map<String, SharedRoute>.from(state.routes);
    next[route.clientId] = route;
    state = state.copyWith(routes: next);
    _ws?.send(buildRouteUpdate(route));
  }

  void clearPublishedRoute() {
    final id = state.clientId;
    if (id != null) {
      final next = Map<String, SharedRoute>.from(state.routes)..remove(id);
      state = state.copyWith(routes: next);
    }
    // Always notify peers — even if local map already cleared.
    _ws?.send(buildRouteClear());
  }

  Future<void> _persistLastTrip() async {
    final s = state;
    if (s.tripId == null || s.inviteCode == null) return;
    await ref.read(profileRepositoryProvider).saveLastTrip(
          tripId: s.tripId!,
          inviteCode: s.inviteCode!,
          tripName: s.tripName ?? '',
          leaderToken: s.leaderToken,
        );
  }

  void _ingestSharedRoutes(dynamic raw) {
    if (raw is! List) return;
    final routes = <String, SharedRoute>{};
    for (final e in raw) {
      if (e is Map) {
        final r = SharedRoute.fromJson(Map<String, dynamic>.from(e));
        if (r.clientId.isNotEmpty && r.points.length >= 2) {
          routes[r.clientId] = r;
        }
      }
    }
    state = state.copyWith(routes: routes);
  }

  void endTrip() {
    final token = state.leaderToken;
    if (token == null) {
      state = state.copyWith(errorMessage: 'Leader only');
      return;
    }
    _ws?.send({
      'type': 'end_trip',
      'version': 1,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
      'leaderToken': token,
    });
  }

  /// From home: reconnect as leader and end the saved trip, or just clear local card.
  Future<bool> deleteSavedTrip() async {
    final last = ref.read(profileRepositoryProvider).getLastTrip();
    if (last == null) return false;
    final token = last['leaderToken'];
    if (token == null || token.isEmpty) {
      await ref.read(profileRepositoryProvider).clearLastTrip();
      return true;
    }
    await joinTrip(
      tripId: last['tripId']!,
      inviteCode: last['inviteCode']!,
      leaderToken: token,
    );
    for (var i = 0; i < 40; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 100));
      if (state.joined) break;
    }
    if (!state.joined) {
      await leave();
      return false;
    }
    endTrip();
    await Future<void>.delayed(const Duration(milliseconds: 700));
    if (state.tripId != null) {
      await leave();
      await ref.read(profileRepositoryProvider).clearLastTrip();
    }
    return true;
  }

  void requestPtt() {
    _ws?.send({
      'type': 'ptt_request',
      'version': 1,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
    });
  }

  void releasePtt() {
    _ws?.send({
      'type': 'ptt_release',
      'version': 1,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
    });
  }

  void sendChatMessage(String text) {
    if (text.trim().isEmpty) return;
    _ws?.send(buildChatMessage(text: text.trim()));
  }

  void sendSignal(Map<String, dynamic> message) {
    _ws?.send(message);
  }

  Future<void> leave() async {
    _ws?.send({
      'type': 'leave',
      'version': 1,
      'timestamp': DateTime.now().millisecondsSinceEpoch,
    });
    await _ws?.close();
    _ws = null;
    state = const ActiveTripState();
  }

  void _onMessage(Map<String, dynamic> raw) {
    final msg = ProtocolMessage.tryParse(raw);
    if (msg == null) return;
    state = state.copyWith(lastServerPacketAt: DateTime.now());

    switch (msg.type) {
      case 'joined':
        final members = (raw['members'] as List<dynamic>? ?? [])
            .map(
              (e) => TripMember.fromJson(Map<String, dynamic>.from(e as Map)),
            )
            .toList();
        TripDestination? dest;
        if (raw['destination'] is Map) {
          dest = TripDestination.fromJson(
            Map<String, dynamic>.from(raw['destination'] as Map),
          );
        }
        final marks = <String, MapMark>{};
        final marksRaw = raw['marks'] as List<dynamic>? ?? [];
        for (final e in marksRaw) {
          if (e is Map) {
            final mark = MapMark.fromJson(Map<String, dynamic>.from(e));
            if (mark.clientId.isNotEmpty) marks[mark.clientId] = mark;
          }
        }
        state = state.copyWith(
          joined: true,
          tripId: raw['tripId'] as String? ?? state.tripId,
          tripName: raw['tripName'] as String? ?? state.tripName,
          leaderId: raw['leaderId'] as String?,
          clientId: raw['clientId'] as String? ?? state.clientId,
          inviteCode: raw['inviteCode'] as String? ?? state.inviteCode,
          members: members,
          destination: dest,
          marks: marks,
          activeSpeakerId: raw['activeSpeakerId'] as String?,
        );
        unawaited(_persistLastTrip());
        _ingestSharedRoutes(raw['routes']);
      case 'members_snapshot':
        final members = (raw['members'] as List<dynamic>? ?? [])
            .map(
              (e) => TripMember.fromJson(Map<String, dynamic>.from(e as Map)),
            )
            .toList();
        state = state.copyWith(members: members);
      case 'member_joined':
      case 'member_left':
        // snapshot usually follows; ignore light updates if needed
        break;
      case 'location_update':
        final id = raw['clientId'] as String?;
        if (id == null) return;
        final members = [...state.members];
        final idx = members.indexWhere((m) => m.id == id);
        if (idx < 0) return;
        members[idx] = members[idx].copyWith(
          latitude: (raw['latitude'] as num?)?.toDouble(),
          longitude: (raw['longitude'] as num?)?.toDouble(),
          accuracy: (raw['accuracy'] as num?)?.toDouble(),
          speed: (raw['speed'] as num?)?.toDouble(),
          heading: (raw['heading'] as num?)?.toDouble(),
          lastLocationAt: DateTime.fromMillisecondsSinceEpoch(msg.timestamp),
          lastSeenAt: DateTime.now(),
          connectionStatus: MemberConnectionStatus.connected,
        );
        state = state.copyWith(members: members);
      case 'destination_update':
        if (raw['destination'] is Map) {
          state = state.copyWith(
            destination: TripDestination.fromJson(
              Map<String, dynamic>.from(raw['destination'] as Map),
            ),
          );
        }
      case 'map_mark':
        if (raw['mark'] is Map) {
          final mark = MapMark.fromJson(
            Map<String, dynamic>.from(raw['mark'] as Map),
          );
          // Own mark already applied optimistically; echo can resurrect after clear.
          if (mark.clientId == state.clientId) break;
          final next = Map<String, MapMark>.from(state.marks);
          next[mark.clientId] = mark;
          state = state.copyWith(marks: next);
        }
      case 'map_mark_clear':
        final clearId = raw['clientId'] as String?;
        if (clearId != null && clearId != state.clientId) {
          final next = Map<String, MapMark>.from(state.marks)..remove(clearId);
          state = state.copyWith(marks: next);
        }
      case 'marks_snapshot':
        final marks = <String, MapMark>{};
        for (final e in (raw['marks'] as List<dynamic>? ?? [])) {
          if (e is Map) {
            final mark = MapMark.fromJson(Map<String, dynamic>.from(e));
            if (mark.clientId.isNotEmpty) marks[mark.clientId] = mark;
          }
        }
        state = state.copyWith(marks: marks);
      case 'route_update':
        final route = SharedRoute.fromJson(raw);
        if (route.clientId.isNotEmpty &&
            route.clientId != state.clientId &&
            route.points.length >= 2) {
          final next = Map<String, SharedRoute>.from(state.routes);
          next[route.clientId] = route;
          state = state.copyWith(routes: next);
        }
      case 'route_clear':
        final rid = raw['clientId'] as String?;
        if (rid != null && rid != state.clientId) {
          final next = Map<String, SharedRoute>.from(state.routes)..remove(rid);
          state = state.copyWith(routes: next);
        }
      case 'leader_change':
        state = state.copyWith(leaderId: raw['leaderId'] as String?);
      case 'ptt_granted':
        state = state.copyWith(
          activeSpeakerId: raw['clientId'] as String?,
          activeSpeakerName: raw['displayName'] as String?,
        );
      case 'ptt_busy':
        state = state.copyWith(
          activeSpeakerId: raw['activeSpeakerId'] as String?,
          errorMessage: 'ptt_busy',
        );
      case 'ptt_release':
        state = state.copyWith(clearSpeaker: true);
      case 'webrtc_offer':
      case 'webrtc_answer':
      case 'ice_candidate':
        ref.read(webrtcSignalProvider.notifier).offer(raw);
      case 'chat_message':
        final item = {
          'id': '${raw['timestamp']}_${raw['clientId']}',
          'clientId': raw['clientId'],
          'senderName': raw['senderName'] ?? 'Driver',
          'senderColor': raw['senderColor'] ?? '#1976D2',
          'text': raw['text'] ?? '',
          'timestamp': raw['timestamp'] ?? DateTime.now().millisecondsSinceEpoch,
        };
        state = state.copyWith(
          chatMessages: [...state.chatMessages, item],
        );
      case 'trip_ended':
        unawaited(_onTripEnded());
      case 'error':
        state = state.copyWith(
          errorMessage: raw['message'] as String? ?? 'error',
        );
      case 'pong':
        break;
    }
  }

  Future<void> _onTripEnded() async {
    await _ws?.close();
    _ws = null;
    await ref.read(profileRepositoryProvider).clearLastTrip();
    state = const ActiveTripState(errorMessage: 'trip_ended');
  }
}

final tripControllerProvider =
    NotifierProvider<TripController, ActiveTripState>(TripController.new);

/// Simple signal bus for WebRTC layer — queue so ICE isn't dropped.
class WebrtcSignalBus extends Notifier<List<Map<String, dynamic>>> {
  @override
  List<Map<String, dynamic>> build() => const [];

  void offer(Map<String, dynamic> msg) {
    state = [...state, msg];
  }

  Map<String, dynamic>? take() {
    if (state.isEmpty) return null;
    final first = state.first;
    state = state.sublist(1);
    return first;
  }
}

final webrtcSignalProvider =
    NotifierProvider<WebrtcSignalBus, List<Map<String, dynamic>>>(
      WebrtcSignalBus.new,
    );
