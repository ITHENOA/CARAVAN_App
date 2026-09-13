import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_ringtone_player/flutter_ringtone_player.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:geolocator/geolocator.dart';
import 'package:go_router/go_router.dart';
import 'package:maplibre_gl/maplibre_gl.dart' as maplibre;

import '../../../core/networking/ws_client.dart';
import '../../../core/theme/caravan_theme.dart';
import '../../../core/utils/convoy_colors.dart';
import '../../../core/utils/geo.dart';
import '../../../l10n/app_localizations.dart';
import '../../chat/data/quick_prompts_controller.dart';
import '../../destination/data/route_controller.dart';
import '../../location/data/location_controller.dart';
import '../../map/presentation/trip_map_view.dart';
import '../../onboarding/presentation/profile_provider.dart';
import '../../settings/presentation/settings_controller.dart';
import '../../voice/data/voice_controller.dart';
import '../../members/domain/member.dart';
import '../data/trip_controller.dart';

class TripScreen extends ConsumerStatefulWidget {
  const TripScreen({super.key});

  @override
  ConsumerState<TripScreen> createState() => _TripScreenState();
}

class _TripScreenState extends ConsumerState<TripScreen> {
  maplibre.MapLibreMapController? _mapController;
  bool _showQuickPrompts = false;
  bool _topExpanded = false;
  int _lastKnownChatCount = 0;
  final List<Map<String, dynamic>> _visiblePreviews = [];
  Timer? _cleanupTimer;
  String? _focusMemberId;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      ref.read(locationControllerProvider.notifier).start();
    });
    _cleanupTimer = Timer.periodic(const Duration(seconds: 2), (_) {
      final now = DateTime.now().millisecondsSinceEpoch;
      if (mounted) {
        setState(() {
          _visiblePreviews.removeWhere(
            (m) => (now - (m['addedAt'] as int? ?? 0)) > 20000,
          );
        });
      }
    });
  }

  @override
  void dispose() {
    _cleanupTimer?.cancel();
    super.dispose();
  }

  void _onNewChatMessage(Map<String, dynamic> msg) {
    final now = DateTime.now().millisecondsSinceEpoch;
    final previewItem = Map<String, dynamic>.from(msg);
    previewItem['addedAt'] = now;
    setState(() {
      _visiblePreviews.add(previewItem);
      if (_visiblePreviews.length > 4) _visiblePreviews.removeAt(0);
    });
    HapticFeedback.mediumImpact();
    final trip = ref.read(tripControllerProvider);
    final fromSelf = msg['clientId'] == trip.clientId;
    if (!fromSelf) {
      unawaited(_playMessageSound());
    }
  }

  Future<void> _playMessageSound() async {
    try {
      // asAlarm helps when WebRTC / media session ducks notification audio.
      await FlutterRingtonePlayer().playNotification(
        volume: 1,
        asAlarm: true,
      );
    } catch (_) {
      try {
        await SystemSound.play(SystemSoundType.alert);
      } catch (_) {}
    }
  }

  Future<void> _recenterMap() async {
    final l10n = AppLocalizations.of(context);
    final serviceOn = await Geolocator.isLocationServiceEnabled();
    if (!serviceOn) {
      if (!mounted) return;
      final ok = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(l10n.enableGpsTitle),
          content: Text(l10n.enableGpsMessage),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(l10n.cancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(l10n.enableGps),
            ),
          ],
        ),
      );
      if (ok == true) await Geolocator.openLocationSettings();
      return;
    }

    final locOk =
        await ref.read(locationControllerProvider.notifier).ensurePermission();
    if (!locOk) {
      if (!mounted) return;
      final open = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(l10n.enableGpsTitle),
          content: Text(l10n.enableGpsMessage),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context, false),
              child: Text(l10n.cancel),
            ),
            FilledButton(
              onPressed: () => Navigator.pop(context, true),
              child: Text(l10n.enableGps),
            ),
          ],
        ),
      );
      if (open == true) await Geolocator.openAppSettings();
      return;
    }

    await ref.read(locationControllerProvider.notifier).start();
    final loc = ref.read(locationControllerProvider);
    if (loc != null && _mapController != null) {
      await _mapController!.animateCamera(
        maplibre.CameraUpdate.newLatLngZoom(
          maplibre.LatLng(loc.latitude, loc.longitude),
          15,
        ),
      );
    }
  }

  void _sendQuickPrompt(String text) {
    ref.read(tripControllerProvider.notifier).sendChatMessage(text);
    setState(() => _showQuickPrompts = false);
    HapticFeedback.selectionClick();
  }

  String _myMarkColor() {
    final trip = ref.read(tripControllerProvider);
    final profile = ref.read(profileProvider).valueOrNull;
    final taken = trip.members
        .where((m) => m.id != trip.clientId)
        .map((m) => m.avatarColor ?? '')
        .where((c) => c.isNotEmpty);
    return distinctColorFor(
      trip.clientId ?? profile?.clientId ?? 'self',
      preferred: profile?.avatarColor,
      taken: taken,
    );
  }

  void _onLongPressMark(LatLng pos) {
    final color = _myMarkColor();
    ref
        .read(tripControllerProvider.notifier)
        .setMapMark(
          latitude: pos.latitude,
          longitude: pos.longitude,
          color: color,
        );
    // Mark only — do not route yet.
    ref.read(routeControllerProvider.notifier).clearPreviewKeepMark();
    HapticFeedback.mediumImpact();
  }

  void _onTapClearMark() {
    final trip = ref.read(tripControllerProvider);
    if (trip.myMark == null) return;
    ref.read(tripControllerProvider.notifier).clearMapMark();
    ref.read(routeControllerProvider.notifier).stopNavigation();
    HapticFeedback.selectionClick();
  }

  Future<void> _onRouteButton(RoutingProvider provider) async {
    final mark = ref.read(tripControllerProvider).myMark;
    if (mark == null) return;
    HapticFeedback.mediumImpact();
    await ref
        .read(routeControllerProvider.notifier)
        .previewRouteTo(
          LatLng(mark.latitude, mark.longitude),
          provider: provider,
        );
  }

  void _joinMemberRoute(SharedRoute route) {
    HapticFeedback.mediumImpact();
    ref.read(routeControllerProvider.notifier).joinSharedRoute(route);
    // Drop a mark at their destination so routing buttons stay available.
    final dest = route.points.last;
    ref.read(tripControllerProvider.notifier).setMapMark(
          latitude: dest.latitude,
          longitude: dest.longitude,
          color: _myMarkColor(),
        );
  }

  bool _samePoint(LatLng a, LatLng b) =>
      (a.latitude - b.latitude).abs() < 1e-5 &&
      (a.longitude - b.longitude).abs() < 1e-5;

  String _pttStatusLabel(AppLocalizations l10n, VoiceState voice) {
    if (voice.latched &&
        (voice.ui == PttUiState.transmitting ||
            voice.ui == PttUiState.requesting)) {
      return 'Tap to stop';
    }
    switch (voice.ui) {
      case PttUiState.requesting:
        return l10n.pttRequesting;
      case PttUiState.transmitting:
        return l10n.pttLive;
      case PttUiState.busy:
        return l10n.pttBusy;
      case PttUiState.unavailable:
        switch (voice.error) {
          case 'mic':
            return l10n.micPermissionRequired;
          case 'ice':
            return l10n.pttIceFailed;
          case 'timeout':
            return l10n.pttTimeout;
          default:
            return l10n.pttFailed;
        }
      case PttUiState.listening:
      case PttUiState.idle:
        return 'Hold or tap';
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final trip = ref.watch(tripControllerProvider);
    final voice = ref.watch(voiceControllerProvider);
    final routeUi = ref.watch(routeControllerProvider);
    final prompts =
        ref.watch(quickPromptsProvider).valueOrNull ?? defaultQuickPrompts;
    final metric = ref.watch(settingsControllerProvider).metric;
    final cs = Theme.of(context).colorScheme;
    final myMark = trip.myMark;

    if (trip.chatMessages.length > _lastKnownChatCount) {
      final newItems = trip.chatMessages.sublist(_lastKnownChatCount);
      _lastKnownChatCount = trip.chatMessages.length;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        for (final m in newItems) {
          _onNewChatMessage(m);
        }
      });
    }

    String connLabel;
    Color connColor;
    switch (trip.connection) {
      case WsConnectionState.connected:
        connLabel = l10n.connected;
        connColor = const Color(0xFF16A34A);
      case WsConnectionState.reconnecting:
      case WsConnectionState.connecting:
        connLabel = l10n.reconnecting;
        connColor = const Color(0xFFF59E0B);
      case WsConnectionState.disconnected:
        connLabel = l10n.offline;
        connColor = CaravanTheme.danger;
    }

    final showEtaStrip =
        routeUi.phase == NavPhase.preview ||
        routeUi.phase == NavPhase.navigating;

    ref.listen(routeControllerProvider, (prev, next) {
      if (next.error == null || next.error == prev?.error) return;
      final message = next.error == 'no_gps'
          ? l10n.waitingForGps
          : l10n.routingFailed;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!context.mounted) return;
        ScaffoldMessenger.of(
          context,
        ).showSnackBar(SnackBar(content: Text(message)));
      });
    });

    ref.listen(tripControllerProvider, (prev, next) {
      if (next.errorMessage == 'trip_ended' && (prev?.joined ?? false)) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (!context.mounted) return;
          ScaffoldMessenger.of(
            context,
          ).showSnackBar(SnackBar(content: Text(l10n.tripDeleted)));
          context.go('/home');
        });
      }
    });

    return Scaffold(
      body: Stack(
        children: [
          Positioned.fill(
            child: TripMapView(
              focusMemberId: _focusMemberId,
              onMapReady: (ctrl) => _mapController = ctrl,
              onLongPressMark: _onLongPressMark,
              onTapClearMark: _onTapClearMark,
            ),
          ),

          // Hidden sinks — Android needs mounted RTCVideoView to play remote audio.
          // Watch peerStates so this rebuilds when a new remote track arrives.
          if (voice.peerStates.isNotEmpty)
            ...[
              for (final r in ref
                  .read(voiceControllerProvider.notifier)
                  .remoteRenderers)
                Positioned(
                  left: -2,
                  top: -2,
                  width: 1,
                  height: 1,
                  child: RTCVideoView(r),
                ),
            ],

          // Top stack: main panel, then ETA strip underneath with a small gap
          Positioned(
            top: 0,
            left: 0,
            right: 0,
            child: SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 8, 14, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    _TopTripPanel(
                      expanded: _topExpanded,
                      tripName: trip.tripName ?? l10n.appTitle,
                      inviteCode: trip.inviteCode ?? '—',
                      memberCount: trip.members.length,
                      connLabel: connLabel,
                      connColor: connColor,
                      members: trip.members,
                      clientId: trip.clientId,
                      l10n: l10n,
                      onToggle: () =>
                          setState(() => _topExpanded = !_topExpanded),
                      onSettings: () => context.push('/settings'),
                      onLeave: () => _leave(context),
                      onMemberTap: (id) {
                        setState(() {
                          _focusMemberId = id;
                          _topExpanded = false;
                        });
                        Future<void>.delayed(
                          const Duration(milliseconds: 400),
                          () {
                            if (mounted) setState(() => _focusMemberId = null);
                          },
                        );
                      },
                    ),
                    if (showEtaStrip) ...[
                      const SizedBox(height: 8),
                      _EtaStrip(
                        routeUi: routeUi,
                        l10n: l10n,
                        metric: metric,
                        onStart: () {
                          ref
                              .read(routeControllerProvider.notifier)
                              .startNavigation();
                          HapticFeedback.heavyImpact();
                        },
                        onStop: () {
                          ref
                              .read(routeControllerProvider.notifier)
                              .stopNavigation();
                        },
                      ),
                    ],
                  ],
                ),
              ),
            ),
          ),

          if (trip.activeSpeakerName != null || trip.activeSpeakerId != null)
            Positioned(
              top: _topExpanded ? 280 : (showEtaStrip ? 150 : 90),
              left: 16,
              child: SafeArea(
                child: Chip(
                  avatar: const Icon(
                    Icons.record_voice_over,
                    size: 16,
                    color: Colors.white,
                  ),
                  backgroundColor: CaravanTheme.danger,
                  label: Text(
                    l10n.isTalking(
                      trip.activeSpeakerName ?? trip.activeSpeakerId ?? '',
                    ),
                    style: const TextStyle(
                      color: Colors.white,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
            ),

          Positioned(
            top: _topExpanded ? 280 : (showEtaStrip ? 150 : 90),
            right: 14,
            width: 220,
            child: SafeArea(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  for (final msg in _visiblePreviews)
                    _PreviewBubble(
                      name: msg['senderName'] as String? ?? l10n.driverFallback,
                      text: msg['text'] as String? ?? '',
                    ),
                ],
              ),
            ),
          ),

          // Map chrome: zoom + route + recenter (Google Maps style cluster)
          Positioned(
            right: 16,
            bottom: 110,
            child: SafeArea(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  _MapCircleButton(
                    icon: Icons.add,
                    onTap: () => _mapController?.animateCamera(
                      maplibre.CameraUpdate.zoomIn(),
                    ),
                  ),
                  const SizedBox(height: 8),
                  _MapCircleButton(
                    icon: Icons.remove,
                    onTap: () => _mapController?.animateCamera(
                      maplibre.CameraUpdate.zoomOut(),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (myMark != null) ...[
                        _MapCircleButton(
                          icon: Icons.traffic,
                          loading:
                              routeUi.loading &&
                              routeUi.loadingProvider == RoutingProvider.neshan,
                          active:
                              routeUi.phase != NavPhase.idle &&
                              routeUi.provider == RoutingProvider.neshan,
                          activeColor: CaravanTheme.routeLine,
                          onTap: () => _onRouteButton(RoutingProvider.neshan),
                        ),
                        const SizedBox(width: 8),
                        _MapCircleButton(
                          icon: Icons.directions,
                          loading:
                              routeUi.loading &&
                              routeUi.loadingProvider == RoutingProvider.osrm,
                          active:
                              routeUi.phase != NavPhase.idle &&
                              routeUi.provider == RoutingProvider.osrm,
                          activeColor: CaravanTheme.routeLine,
                          onTap: () => _onRouteButton(RoutingProvider.osrm),
                        ),
                        const SizedBox(width: 8),
                      ],
                      // Join another member's published route.
                      for (final entry in trip.routes.entries)
                        if (entry.key != trip.clientId &&
                            entry.value.points.length >= 2) ...[
                          _MapCircleButton(
                            icon: Icons.alt_route,
                            tint: Color(
                              int.parse(
                                'FF${normalizeHexColor(entry.value.colorHex).replaceAll('#', '')}',
                                radix: 16,
                              ),
                            ),
                            active: routeUi.phase != NavPhase.idle &&
                                routeUi.target != null &&
                                _samePoint(
                                  routeUi.target!,
                                  entry.value.points.last,
                                ),
                            activeColor: Color(
                              int.parse(
                                'FF${normalizeHexColor(entry.value.colorHex).replaceAll('#', '')}',
                                radix: 16,
                              ),
                            ),
                            onTap: () => _joinMemberRoute(entry.value),
                          ),
                          const SizedBox(width: 8),
                        ],
                      _MapCircleButton(
                        icon: Icons.my_location,
                        onTap: _recenterMap,
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),

          if (_showQuickPrompts)
            Positioned(
              left: 16,
              right: 16,
              bottom: 110,
              child: SafeArea(
                child: Material(
                  elevation: 8,
                  borderRadius: BorderRadius.circular(22),
                  color: cs.surface.withValues(alpha: 0.97),
                  child: Padding(
                    padding: const EdgeInsets.all(14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Row(
                          children: [
                            Text(
                              l10n.quickMessages,
                              style: const TextStyle(
                                fontWeight: FontWeight.w800,
                              ),
                            ),
                            const Spacer(),
                            TextButton(
                              onPressed: () {
                                setState(() => _showQuickPrompts = false);
                                context.push('/chat');
                              },
                              child: Text(l10n.openChat),
                            ),
                          ],
                        ),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: [
                            for (final p in prompts)
                              ActionChip(
                                label: Text(p),
                                onPressed: () => _sendQuickPrompt(p),
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
              ),
            ),

          // Bottom: PTT + Chat only
          Positioned(
            left: 18,
            right: 18,
            bottom: 18,
            child: SafeArea(
              child: Row(
                children: [
                  Expanded(
                    child: _PttButton(
                      voice: voice,
                      label: _pttStatusLabel(l10n, voice),
                    ),
                  ),
                  const SizedBox(width: 10),
                  _ChatFab(
                    hasMessages: trip.chatMessages.isNotEmpty,
                    onTap: () {
                      setState(() => _showQuickPrompts = !_showQuickPrompts);
                      HapticFeedback.selectionClick();
                    },
                    onLongPress: () => context.push('/chat'),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _leave(BuildContext context) async {
    await ref.read(locationControllerProvider.notifier).stop();
    await ref.read(voiceControllerProvider.notifier).disposeVoice();
    ref.read(routeControllerProvider.notifier).stopNavigation();
    await ref.read(tripControllerProvider.notifier).leave();
    if (context.mounted) context.go('/home');
  }
}

class _EtaStrip extends StatelessWidget {
  const _EtaStrip({
    required this.routeUi,
    required this.l10n,
    required this.metric,
    required this.onStart,
    required this.onStop,
  });

  final RouteUiState routeUi;
  final AppLocalizations l10n;
  final bool metric;
  final VoidCallback onStart;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final route = routeUi.route;
    final navigating = routeUi.phase == NavPhase.navigating;
    final summary = route == null
        ? (routeUi.loading ? '…' : l10n.routingFailed)
        : l10n.distanceEta(route.distanceLabel(metric: metric), route.etaLabel);

    return Material(
      elevation: 3,
      color: cs.primaryContainer,
      borderRadius: BorderRadius.circular(18),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            Icon(
              navigating ? Icons.navigation : Icons.alt_route,
              color: cs.onPrimaryContainer,
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    summary,
                    style: TextStyle(
                      fontWeight: FontWeight.w800,
                      color: cs.onPrimaryContainer,
                      fontSize: 15,
                    ),
                  ),
                  Text(
                    navigating ? 'Navigation active' : l10n.routeEta,
                    style: TextStyle(
                      fontSize: 11,
                      color: cs.onPrimaryContainer.withValues(alpha: 0.75),
                    ),
                  ),
                ],
              ),
            ),
            if (navigating)
              TextButton(onPressed: onStop, child: const Text('Stop'))
            else
              FilledButton(
                onPressed: route == null || routeUi.loading ? null : onStart,
                child: Text(l10n.startNavigation),
              ),
          ],
        ),
      ),
    );
  }
}

List<Widget> _memberTiles({
  required List<TripMember> members,
  required String? clientId,
  required AppLocalizations l10n,
  required void Function(String id) onMemberTap,
}) {
  final taken = <String>{};
  final tiles = <Widget>[];
  for (final m in members) {
    final color = distinctColorFor(
      m.id,
      preferred: m.avatarColor,
      taken: taken,
    );
    taken.add(normalizeHexColor(color));
    tiles.add(
      ListTile(
        dense: true,
        contentPadding: EdgeInsets.zero,
        leading: CircleAvatar(
          radius: 10,
          backgroundColor: Color(
            int.parse('FF${color.replaceAll('#', '')}', radix: 16),
          ),
        ),
        title: Text(
          m.displayName +
              (m.id == clientId ? ' (${l10n.you})' : '') +
              (m.isLeader ? ' · ${l10n.leader}' : ''),
        ),
        trailing: const Icon(Icons.my_location, size: 18),
        onTap: () => onMemberTap(m.id),
      ),
    );
  }
  return tiles;
}

class _TopTripPanel extends StatelessWidget {
  const _TopTripPanel({
    required this.expanded,
    required this.tripName,
    required this.inviteCode,
    required this.memberCount,
    required this.connLabel,
    required this.connColor,
    required this.members,
    required this.clientId,
    required this.l10n,
    required this.onToggle,
    required this.onSettings,
    required this.onLeave,
    required this.onMemberTap,
  });

  final bool expanded;
  final String tripName;
  final String inviteCode;
  final int memberCount;
  final String connLabel;
  final Color connColor;
  final List<TripMember> members;
  final String? clientId;
  final AppLocalizations l10n;
  final VoidCallback onToggle;
  final VoidCallback onSettings;
  final VoidCallback onLeave;
  final void Function(String id) onMemberTap;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return Material(
      elevation: 3,
      color: cs.surface.withValues(alpha: 0.95),
      borderRadius: BorderRadius.circular(22),
      child: AnimatedSize(
        duration: const Duration(milliseconds: 240),
        curve: Curves.easeOutCubic,
        alignment: Alignment.topCenter,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 8, 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              InkWell(
                onTap: onToggle,
                borderRadius: BorderRadius.circular(12),
                child: Row(
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            tripName,
                            style: const TextStyle(
                              fontWeight: FontWeight.w800,
                              fontSize: 15,
                            ),
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '${l10n.inviteCode}: $inviteCode · ${l10n.carsInConvoy(memberCount)}',
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(
                        horizontal: 8,
                        vertical: 4,
                      ),
                      decoration: BoxDecoration(
                        color: connColor.withValues(alpha: 0.15),
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Row(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Icon(Icons.circle, size: 8, color: connColor),
                          const SizedBox(width: 4),
                          Text(
                            connLabel,
                            style: TextStyle(
                              fontSize: 11,
                              color: connColor,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                    Icon(
                      expanded ? Icons.expand_less : Icons.expand_more,
                      color: cs.onSurfaceVariant,
                    ),
                    IconButton(
                      visualDensity: VisualDensity.compact,
                      onPressed: onSettings,
                      icon: const Icon(Icons.settings_outlined, size: 20),
                    ),
                  ],
                ),
              ),
              if (expanded) ...[
                const Divider(height: 18),
                Text(
                  l10n.members,
                  style: const TextStyle(fontWeight: FontWeight.w800),
                ),
                const SizedBox(height: 6),
                ..._memberTiles(
                  members: members,
                  clientId: clientId,
                  l10n: l10n,
                  onMemberTap: onMemberTap,
                ),
                const SizedBox(height: 4),
                OutlinedButton.icon(
                  onPressed: onLeave,
                  icon: const Icon(Icons.exit_to_app, color: Colors.red),
                  label: Text(
                    l10n.leaveTrip,
                    style: const TextStyle(color: Colors.red),
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _PreviewBubble extends StatelessWidget {
  const _PreviewBubble({required this.name, required this.text});
  final String name;
  final String text;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.72),
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: Colors.white.withValues(alpha: 0.18)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            name,
            style: const TextStyle(
              color: Color(0xFF7DD3FC),
              fontSize: 10,
              fontWeight: FontWeight.bold,
            ),
          ),
          Text(
            text,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 13,
              fontWeight: FontWeight.w600,
            ),
          ),
        ],
      ),
    );
  }
}

class _MapCircleButton extends StatelessWidget {
  const _MapCircleButton({
    required this.icon,
    required this.onTap,
    this.loading = false,
    this.active = false,
    this.activeColor,
    this.tint,
  });

  final IconData icon;
  final VoidCallback onTap;
  final bool loading;
  final bool active;
  final Color? activeColor;
  /// Always-on accent (e.g. member color for join-route buttons).
  final Color? tint;

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    final bg = active
        ? (activeColor ?? tint ?? cs.primary)
        : (tint?.withValues(alpha: 0.92) ?? cs.surface);
    final fg = active || tint != null ? Colors.white : cs.primary;
    return Material(
      elevation: 3,
      color: bg.withValues(alpha: active ? 1 : 0.96),
      shape: const CircleBorder(),
      child: InkWell(
        customBorder: const CircleBorder(),
        onTap: loading ? null : onTap,
        child: SizedBox(
          width: 48,
          height: 48,
          child: Center(
            child: loading
                ? SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2.4,
                      color: fg,
                    ),
                  )
                : Icon(icon, color: fg, size: 22),
          ),
        ),
      ),
    );
  }
}

class _PttButton extends StatefulWidget {
  const _PttButton({required this.voice, required this.label});

  final VoiceState voice;
  final String label;

  @override
  State<_PttButton> createState() => _PttButtonState();
}

class _PttButtonState extends State<_PttButton> {
  Timer? _holdTimer;
  bool _pointerDown = false;
  bool _holdStarted = false;

  @override
  void dispose() {
    _holdTimer?.cancel();
    super.dispose();
  }

  void _onDown(WidgetRef ref) {
    if (_pointerDown) return;
    _pointerDown = true;
    _holdStarted = false;
    HapticFeedback.mediumImpact();
    _holdTimer?.cancel();
    _holdTimer = Timer(const Duration(milliseconds: 250), () {
      if (!_pointerDown || !mounted) return;
      _holdStarted = true;
      ref.read(voiceControllerProvider.notifier).beginMomentary();
    });
  }

  void _onUp(WidgetRef ref, {required bool isCancel}) {
    if (!_pointerDown) return;
    _pointerDown = false;
    _holdTimer?.cancel();
    if (_holdStarted) {
      _holdStarted = false;
      ref.read(voiceControllerProvider.notifier).endMomentary();
      return;
    }
    // Short press → toggle latch. Ignore cancel (finger slid off).
    if (!isCancel) {
      ref.read(voiceControllerProvider.notifier).togglePtt();
    }
  }

  @override
  Widget build(BuildContext context) {
    final voice = widget.voice;
    final live =
        voice.ui == PttUiState.transmitting || voice.latched;
    final requesting = voice.ui == PttUiState.requesting;
    final failed =
        voice.ui == PttUiState.unavailable || voice.ui == PttUiState.busy;
    final color = live
        ? CaravanTheme.danger
        : failed
        ? const Color(0xFFEA580C)
        : Theme.of(context).colorScheme.primary;

    return Consumer(
      builder: (context, ref, _) {
        return Listener(
          behavior: HitTestBehavior.opaque,
          onPointerDown: (_) => _onDown(ref),
          onPointerUp: (_) => _onUp(ref, isCancel: false),
          onPointerCancel: (_) => _onUp(ref, isCancel: true),
          child: AnimatedScale(
            scale: live || requesting ? 1.03 : 1,
            duration: const Duration(milliseconds: 120),
            child: AnimatedContainer(
              duration: const Duration(milliseconds: 140),
              height: 64,
              decoration: BoxDecoration(
                color: color,
                borderRadius: BorderRadius.circular(22),
                boxShadow: [
                  BoxShadow(
                    color: color.withValues(alpha: 0.35),
                    blurRadius: 16,
                    offset: const Offset(0, 6),
                  ),
                ],
              ),
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Icon(
                    live ? Icons.mic : Icons.mic_none_rounded,
                    color: Colors.white,
                    size: 28,
                  ),
                  const SizedBox(width: 10),
                  Flexible(
                    child: Text(
                      widget.label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w800,
                        fontSize: 15,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}

class _ChatFab extends StatelessWidget {
  const _ChatFab({
    required this.hasMessages,
    required this.onTap,
    required this.onLongPress,
  });

  final bool hasMessages;
  final VoidCallback onTap;
  final VoidCallback onLongPress;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      onLongPress: onLongPress,
      child: Container(
        width: 64,
        height: 64,
        decoration: BoxDecoration(
          color: CaravanTheme.accent,
          borderRadius: BorderRadius.circular(22),
          boxShadow: [
            BoxShadow(
              color: CaravanTheme.accent.withValues(alpha: 0.35),
              blurRadius: 14,
              offset: const Offset(0, 5),
            ),
          ],
        ),
        alignment: Alignment.center,
        child: Stack(
          alignment: Alignment.center,
          children: [
            const Icon(
              Icons.chat_bubble_rounded,
              color: Colors.white,
              size: 28,
            ),
            if (hasMessages)
              Positioned(
                top: 14,
                right: 14,
                child: Container(
                  width: 9,
                  height: 9,
                  decoration: const BoxDecoration(
                    color: Color(0xFFFBBF24),
                    shape: BoxShape.circle,
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
