import 'dart:math' show Point;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:maplibre_gl/maplibre_gl.dart';

import '../../../core/config/app_config.dart';
import '../../../core/utils/convoy_colors.dart';
import '../../../core/utils/geo.dart' as geo;
import '../../destination/data/route_controller.dart';
import '../../destination/data/routing_service.dart';
import '../../location/data/location_controller.dart';
import '../../members/domain/member.dart';
import '../../trip/data/trip_controller.dart';
import '../data/map_marker_icons.dart';

class TripMapView extends ConsumerStatefulWidget {
  const TripMapView({
    super.key,
    required this.onLongPressMark,
    required this.onTapClearMark,
    this.onMapReady,
    this.focusMemberId,
  });

  final void Function(geo.LatLng position) onLongPressMark;
  final VoidCallback onTapClearMark;
  final void Function(MapLibreMapController controller)? onMapReady;
  final String? focusMemberId;

  @override
  ConsumerState<TripMapView> createState() => _TripMapViewState();
}

class _TripMapViewState extends ConsumerState<TripMapView> {
  MapLibreMapController? _controller;
  bool _styleReady = false;
  final Map<String, Circle> _memberCircles = {};
  final Map<String, Symbol> _memberLabels = {};
  final Map<String, Symbol> _markSymbols = {};
  final Map<String, Circle> _markCircles = {};
  final Set<String> _loadedPinImages = {};
  bool _navArrowLoaded = false;
  bool _selfDotLoaded = false;
  Symbol? _selfSymbol;
  VoidCallback? _symbolTapSub;
  final List<Line> _routeLines = [];
  final Map<String, List<Line>> _sharedRouteLines = {};
  bool _trafficAdded = false;
  String? _lastFocusedMember;
  bool _wasNavigating = false;
  DateTime? _suppressClearUntil;
  // ponytail: one chain per layer; coalesce if MapLibre thrashing under load
  Future<void> _membersChain = Future.value();
  Future<void> _marksChain = Future.value();
  Future<void> _sharedRoutesChain = Future.value();
  Future<void> _routeChain = Future.value();
  int _annoEpoch = 0;

  void _queueMembers(List<TripMember> members, String? selfId) {
    _membersChain = _membersChain
        .catchError((_) {})
        .then((_) => _syncMembers(members, selfId));
  }

  void _queueMarks(
    Map<String, MapMark> marks,
    String? selfId,
    List<TripMember> members,
  ) {
    _marksChain = _marksChain
        .catchError((_) {})
        .then((_) => _syncMarks(marks, selfId, members));
  }

  void _queueSharedRoutes(Map<String, SharedRoute> routes, String? selfId) {
    _sharedRoutesChain = _sharedRoutesChain
        .catchError((_) {})
        .then((_) => _syncSharedRoutes(routes, selfId));
  }

  void _queueRoute(RouteResult? route, {required bool show}) {
    _routeChain =
        _routeChain.catchError((_) {}).then((_) => _syncRoute(route, show: show));
  }

  void _forgetAnnotations() {
    _annoEpoch++;
    _memberCircles.clear();
    _memberLabels.clear();
    _markSymbols.clear();
    _markCircles.clear();
    _routeLines.clear();
    _sharedRouteLines.clear();
    _selfSymbol = null;
  }

  @override
  Widget build(BuildContext context) {
    final trip = ref.watch(tripControllerProvider);
    final me = ref.watch(locationControllerProvider);
    final routeState = ref.watch(routeControllerProvider);
    final navigating = routeState.phase == NavPhase.navigating;

    ref.listen(locationControllerProvider, (prev, next) {
      if (next != null) {
        _upsertSelf(next, navigating: navigating);
      }
    });
    ref.listen(tripControllerProvider, (prev, next) {
      _queueMembers(next.members, next.clientId);
      _queueMarks(next.marks, next.clientId, next.members);
      _queueSharedRoutes(next.routes, next.clientId);
    });
    ref.listen(routeControllerProvider, (prev, next) {
      _queueRoute(next.route, show: next.phase != NavPhase.idle);
      _queueSharedRoutes(
        ref.read(tripControllerProvider).routes,
        ref.read(tripControllerProvider).clientId,
      );
      final nowNav = next.phase == NavPhase.navigating;
      if (_wasNavigating && !nowNav) {
        _resetCameraFlat();
      }
      _wasNavigating = nowNav;
      final loc = ref.read(locationControllerProvider);
      if (loc != null) {
        _upsertSelf(loc, navigating: nowNav);
      }
    });

    if (widget.focusMemberId != null &&
        widget.focusMemberId != _lastFocusedMember) {
      _lastFocusedMember = widget.focusMemberId;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _focusMember(widget.focusMemberId!);
      });
    }

    return Stack(
      children: [
        MapLibreMap(
          styleString: AppConfig.current.mapStyleUrl,
          initialCameraPosition: CameraPosition(
            target: LatLng(me?.latitude ?? 35.6892, me?.longitude ?? 51.389),
            zoom: 14,
            tilt: 0,
            bearing: 0,
          ),
          myLocationEnabled: false,
          compassEnabled: true,
          trackCameraPosition: true,
          attributionButtonMargins: const Point(8, 8),
          logoViewMargins: const Point(8, 8),
          onMapCreated: (c) async {
            _controller = c;
            _symbolTapSub?.call();
            void onSym(Symbol symbol) {
              final trip = ref.read(tripControllerProvider);
              final id = trip.clientId;
              if (id == null) return;
              final mine = _markSymbols[id];
              if (mine != null && mine.id == symbol.id) {
                widget.onTapClearMark();
              }
            }
            c.onSymbolTapped.add(onSym);
            _symbolTapSub = () => c.onSymbolTapped.remove(onSym);
            widget.onMapReady?.call(c);
          },
          onStyleLoadedCallback: () async {
            _styleReady = true;
            _loadedPinImages.clear();
            _navArrowLoaded = false;
            _selfDotLoaded = false;
            _trafficAdded = false;
            // Style reload drops native annotations; Dart refs are dead.
            _forgetAnnotations();
            final c = _controller;
            if (c != null) {
              await c.setSymbolIconAllowOverlap(true);
              await c.setSymbolIconIgnorePlacement(true);
              await c.setSymbolTextAllowOverlap(true);
              await c.setSymbolTextIgnorePlacement(true);
            }
            await _ensureNavArrow();
            await _ensureSelfDot();
            await _maybeAddTrafficOverlay();
            if (me != null) await _upsertSelf(me, navigating: navigating);
            _queueMembers(trip.members, trip.clientId);
            _queueMarks(trip.marks, trip.clientId, trip.members);
            _queueRoute(
              routeState.route,
              show: routeState.phase != NavPhase.idle,
            );
            _queueSharedRoutes(trip.routes, trip.clientId);
          },
          onMapClick: (point, latLng) {
            final until = _suppressClearUntil;
            if (until != null && DateTime.now().isBefore(until)) return;
            widget.onTapClearMark();
          },
          onMapLongClick: (point, latLng) {
            // Finger-up after long-press often fires a map click — ignore it.
            _suppressClearUntil =
                DateTime.now().add(const Duration(milliseconds: 1600));
            widget.onLongPressMark(
              geo.LatLng(latLng.latitude, latLng.longitude),
            );
          },
        ),
        if (kDebugMode && AppConfig.current.mockMode)
          const Positioned(
            left: 16,
            bottom: 120,
            child: Chip(label: Text('MOCK')),
          ),
      ],
    );
  }

  Future<void> _ensureNavArrow() async {
    final c = _controller;
    if (c == null || !_styleReady || _navArrowLoaded) return;
    final bytes = await MapMarkerIcons.navArrowPng();
    await c.addImage(MapMarkerIcons.navArrowId, bytes);
    _navArrowLoaded = true;
  }

  Future<void> _ensureSelfDot() async {
    final c = _controller;
    if (c == null || !_styleReady || _selfDotLoaded) return;
    final bytes = await MapMarkerIcons.selfDotWithHeadingPng();
    await c.addImage(MapMarkerIcons.selfDotId, bytes);
    _selfDotLoaded = true;
  }

  Future<void> _ensurePinImage(String hex) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final id = MapMarkerIcons.pinImageId(hex);
    if (_loadedPinImages.contains(id)) return;
    try {
      final bytes = await MapMarkerIcons.pinPng(MapMarkerIcons.parseHex(hex));
      await c.addImage(id, bytes);
      _loadedPinImages.add(id);
    } catch (e) {
      try {
        final bytes = await MapMarkerIcons.destinationPinPng();
        await c.addImage(id, bytes);
        _loadedPinImages.add(id);
      } catch (_) {}
    }
  }

  @override
  void dispose() {
    _symbolTapSub?.call();
    super.dispose();
  }

  Future<void> _resetCameraFlat() async {
    final c = _controller;
    final me = ref.read(locationControllerProvider);
    if (c == null || me == null) return;
    await c.animateCamera(
      CameraUpdate.newCameraPosition(
        CameraPosition(
          target: LatLng(me.latitude, me.longitude),
          zoom: 15,
          tilt: 0,
          bearing: 0,
        ),
      ),
    );
  }

  Future<void> _focusMember(String memberId) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final trip = ref.read(tripControllerProvider);
    final me = ref.read(locationControllerProvider);
    if (memberId == trip.clientId && me != null) {
      await c.animateCamera(
        CameraUpdate.newLatLngZoom(LatLng(me.latitude, me.longitude), 16),
      );
      return;
    }
    TripMember? m;
    for (final x in trip.members) {
      if (x.id == memberId) m = x;
    }
    if (m?.latitude == null || m?.longitude == null) return;
    await c.animateCamera(
      CameraUpdate.newLatLngZoom(LatLng(m!.latitude!, m.longitude!), 16),
    );
  }

  Future<void> _maybeAddTrafficOverlay() async {
    final c = _controller;
    final url = AppConfig.current.trafficTileUrl;
    if (c == null || url.isEmpty || _trafficAdded || !_styleReady) return;
    try {
      await c.addSource(
        'traffic-tiles',
        RasterSourceProperties(tiles: [url], tileSize: 256),
      );
      await c.addRasterLayer(
        'traffic-tiles',
        'traffic-layer',
        const RasterLayerProperties(rasterOpacity: 0.55),
      );
      _trafficAdded = true;
    } catch (_) {}
  }

  Future<void> _upsertSelf(
    DeviceLocation me, {
    required bool navigating,
  }) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final target = LatLng(me.latitude, me.longitude);
    await _ensureNavArrow();
    await _ensureSelfDot();

    if (_memberLabels.containsKey('__self__')) {
      await c.removeSymbol(_memberLabels['__self__']!);
      _memberLabels.remove('__self__');
    }

    if (navigating) {
      final options = SymbolOptions(
        geometry: target,
        iconImage: MapMarkerIcons.navArrowId,
        iconSize: 0.9,
        iconRotate: me.heading ?? 0,
        iconAnchor: 'center',
        textField: '',
      );
      if (_selfSymbol != null) {
        await c.updateSymbol(_selfSymbol!, options);
      } else {
        _selfSymbol = await c.addSymbol(options);
      }
      await c.animateCamera(
        CameraUpdate.newCameraPosition(
          CameraPosition(
            target: target,
            zoom: 17.4,
            tilt: 60,
            bearing: me.heading ?? 0,
          ),
        ),
      );
      return;
    }

    // Idle / preview: blue dot + heading cone (rotates with course/compass).
    final options = SymbolOptions(
      geometry: target,
      iconImage: MapMarkerIcons.selfDotId,
      iconSize: 1.05,
      iconRotate: me.heading ?? 0,
      iconAnchor: 'center',
      textField: '',
    );
    if (_selfSymbol != null) {
      await c.updateSymbol(_selfSymbol!, options);
    } else {
      _selfSymbol = await c.addSymbol(options);
    }
  }

  Future<void> _syncMembers(List<TripMember> members, String? selfId) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final epoch = _annoEpoch;
    final taken = <String>{};
    final seen = <String>{};
    for (final m in members) {
      if (epoch != _annoEpoch) return;
      if (m.id == selfId) continue;
      if (m.latitude == null || m.longitude == null) continue;
      seen.add(m.id);
      final target = LatLng(m.latitude!, m.longitude!);
      final color = distinctColorFor(
        m.id,
        preferred: m.avatarColor,
        taken: taken,
      );
      taken.add(normalizeHexColor(color));

      if (_memberCircles.containsKey(m.id)) {
        try {
          await c.updateCircle(
            _memberCircles[m.id]!,
            CircleOptions(
              geometry: target,
              circleRadius: m.isLeader ? 11 : 9,
              circleColor: color,
              circleStrokeWidth: 2.5,
              circleStrokeColor: '#FFFFFF',
            ),
          );
        } catch (_) {
          _memberCircles.remove(m.id);
        }
      }
      if (!_memberCircles.containsKey(m.id)) {
        _memberCircles[m.id] = await c.addCircle(
          CircleOptions(
            geometry: target,
            circleRadius: m.isLeader ? 11 : 9,
            circleColor: color,
            circleStrokeWidth: 2.5,
            circleStrokeColor: '#FFFFFF',
          ),
        );
      }

      final label = m.isLeader ? '★ ${m.displayName}' : m.displayName;
      final options = SymbolOptions(
        geometry: target,
        textField: label,
        textSize: 12,
        textColor: '#212121',
        textHaloColor: '#FFFFFF',
        textHaloWidth: 2,
        textOffset: const Offset(0, 1.3),
      );
      if (_memberLabels.containsKey(m.id)) {
        try {
          await c.updateSymbol(_memberLabels[m.id]!, options);
        } catch (_) {
          _memberLabels.remove(m.id);
        }
      }
      if (!_memberLabels.containsKey(m.id)) {
        _memberLabels[m.id] = await c.addSymbol(options);
      }
    }
    if (epoch != _annoEpoch) return;

    final stale = _memberLabels.keys
        .where((k) => k != '__self__' && !seen.contains(k))
        .toList();
    for (final k in stale) {
      await c.removeSymbol(_memberLabels[k]!);
      _memberLabels.remove(k);
      if (_memberCircles.containsKey(k)) {
        await c.removeCircle(_memberCircles[k]!);
        _memberCircles.remove(k);
      }
    }
  }

  Future<void> _syncMarks(
    Map<String, MapMark> marks,
    String? selfId,
    List<TripMember> members,
  ) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final epoch = _annoEpoch;
    final seen = <String>{};
    final taken = <String>{};

    for (final mark in marks.values) {
      if (epoch != _annoEpoch) return;
      seen.add(mark.clientId);
      final target = LatLng(mark.latitude, mark.longitude);
      final isMine = mark.clientId == selfId;
      String? memberColor;
      for (final m in members) {
        if (m.id == mark.clientId) {
          memberColor = m.avatarColor;
          break;
        }
      }
      final color = distinctColorFor(
        mark.clientId,
        preferred: (mark.color.isNotEmpty ? mark.color : null) ?? memberColor,
        taken: taken,
      );
      taken.add(normalizeHexColor(color));
      await _ensurePinImage(color);
      if (epoch != _annoEpoch) return;
      final pinId = MapMarkerIcons.pinImageId(color);

      final circleOpts = CircleOptions(
        geometry: target,
        circleRadius: 12,
        circleColor: color,
        circleOpacity: 0.95,
        circleStrokeWidth: 3,
        circleStrokeColor: '#FFFFFF',
      );
      if (_markCircles.containsKey(mark.clientId)) {
        try {
          await c.updateCircle(_markCircles[mark.clientId]!, circleOpts);
        } catch (_) {
          _markCircles.remove(mark.clientId);
        }
      }
      if (!_markCircles.containsKey(mark.clientId)) {
        _markCircles[mark.clientId] = await c.addCircle(circleOpts);
      }

      final options = SymbolOptions(
        geometry: target,
        iconImage: pinId,
        iconSize: 1.25,
        iconAnchor: 'bottom',
        iconOpacity: 1,
        textField: isMine ? 'Yours' : mark.displayName,
        textSize: 12,
        textColor: color,
        textHaloColor: '#FFFFFF',
        textHaloWidth: 2,
        textOffset: const Offset(0, 0.9),
        textAnchor: 'top',
      );
      try {
        if (_markSymbols.containsKey(mark.clientId)) {
          await c.updateSymbol(_markSymbols[mark.clientId]!, options);
        } else {
          _markSymbols[mark.clientId] = await c.addSymbol(options);
        }
      } catch (_) {
        try {
          final old = _markSymbols.remove(mark.clientId);
          if (old != null) await c.removeSymbol(old);
        } catch (_) {}
        try {
          _markSymbols[mark.clientId] = await c.addSymbol(options);
        } catch (_) {}
      }
    }
    if (epoch != _annoEpoch) return;

    final staleSym = _markSymbols.keys.where((k) => !seen.contains(k)).toList();
    for (final k in staleSym) {
      await c.removeSymbol(_markSymbols[k]!);
      _markSymbols.remove(k);
    }
    final staleCir =
        _markCircles.keys.where((k) => !seen.contains(k)).toList();
    for (final k in staleCir) {
      await c.removeCircle(_markCircles[k]!);
      _markCircles.remove(k);
    }
  }

  Future<void> _syncRoute(RouteResult? route, {required bool show}) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final epoch = _annoEpoch;

    for (final line in _routeLines) {
      await c.removeLine(line);
    }
    _routeLines.clear();
    if (epoch != _annoEpoch) return;

    if (!show || route == null || route.points.isEmpty) return;

    final stretches = route.segments.isNotEmpty
        ? route.segments
        : [
            RouteSegment(
              points: route.points,
              colorHex: '#2563EB',
            ),
          ];

    for (final stretch in stretches) {
      if (epoch != _annoEpoch) return;
      if (stretch.points.length < 2) continue;
      final geometry = stretch.points
          .map((p) => LatLng(p.latitude, p.longitude))
          .toList();
      final line = await c.addLine(
        LineOptions(
          geometry: geometry,
          lineColor: stretch.colorHex,
          lineWidth: 5.5,
          lineOpacity: 0.92,
          lineJoin: 'round',
        ),
      );
      _routeLines.add(line);
    }
  }

  Future<void> _syncSharedRoutes(
    Map<String, SharedRoute> routes,
    String? selfId,
  ) async {
    final c = _controller;
    if (c == null || !_styleReady) return;
    final epoch = _annoEpoch;
    final seen = <String>{};

    for (final entry in routes.entries) {
      if (epoch != _annoEpoch) return;
      final id = entry.key;
      if (id == selfId) continue;
      final route = entry.value;
      if (route.points.length < 2) continue;
      seen.add(id);

      final existing = _sharedRouteLines[id];
      if (existing != null) {
        for (final line in existing) {
          try {
            await c.removeLine(line);
          } catch (_) {}
        }
      }
      // Always draw the dense main polyline — traffic segments are sparse/truncated.
      final geometry =
          route.points.map((p) => LatLng(p.latitude, p.longitude)).toList();
      _sharedRouteLines[id] = [
        await c.addLine(
          LineOptions(
            geometry: geometry,
            lineColor: route.colorHex,
            lineWidth: 5.0,
            lineOpacity: 0.85,
            lineJoin: 'round',
          ),
        ),
      ];
    }
    if (epoch != _annoEpoch) return;

    final stale =
        _sharedRouteLines.keys.where((k) => !seen.contains(k)).toList();
    for (final k in stale) {
      for (final line in _sharedRouteLines[k] ?? const <Line>[]) {
        try {
          await c.removeLine(line);
        } catch (_) {}
      }
      _sharedRouteLines.remove(k);
    }
  }
}
