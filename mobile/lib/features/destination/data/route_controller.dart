import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/utils/app_logger.dart';
import '../../../core/utils/geo.dart';
import '../../location/data/location_controller.dart';
import '../../members/domain/member.dart';
import '../../onboarding/presentation/profile_provider.dart';
import '../../trip/data/trip_controller.dart';
import 'geocoding_service.dart';
import 'neshan_routing_service.dart';
import 'routing_service.dart';

enum NavPhase { idle, preview, navigating }

/// Which routing backend produced the currently displayed route.
enum RoutingProvider { osrm, neshan }

class RouteUiState {
  const RouteUiState({
    this.route,
    this.loading = false,
    this.error,
    this.phase = NavPhase.idle,
    this.target,
    this.provider,
    this.loadingProvider,
  });

  final RouteResult? route;
  final bool loading;
  final String? error;
  final NavPhase phase;
  final LatLng? target;

  /// Provider of the last *successful* route (matches [route]).
  final RoutingProvider? provider;

  /// Provider currently being requested (for per-button loading UI).
  final RoutingProvider? loadingProvider;

  RouteUiState copyWith({
    RouteResult? route,
    bool clearRoute = false,
    bool? loading,
    String? error,
    bool clearError = false,
    NavPhase? phase,
    LatLng? target,
    bool clearTarget = false,
    RoutingProvider? provider,
    bool clearProvider = false,
    RoutingProvider? loadingProvider,
    bool clearLoadingProvider = false,
  }) {
    return RouteUiState(
      route: clearRoute ? null : (route ?? this.route),
      loading: loading ?? this.loading,
      error: clearError ? null : (error ?? this.error),
      phase: phase ?? this.phase,
      target: clearTarget ? null : (target ?? this.target),
      provider: clearProvider ? null : (provider ?? this.provider),
      loadingProvider: clearLoadingProvider
          ? null
          : (loadingProvider ?? this.loadingProvider),
    );
  }
}

class RouteController extends Notifier<RouteUiState> {
  final _osrm = RoutingService();
  final _neshan = NeshanRoutingService();
  final _geocoding = GeocodingService();

  @override
  RouteUiState build() => const RouteUiState();

  /// Manual route request only — never called from GPS/time/WS listeners.
  Future<void> previewRouteTo(
    LatLng target, {
    required RoutingProvider provider,
  }) async {
    state = state.copyWith(
      target: target,
      phase: NavPhase.preview,
      loading: true,
      loadingProvider: provider,
      clearError: true,
    );
    await _fetch(target, provider);
  }

  Future<void> _fetch(LatLng target, RoutingProvider provider) async {
    final me = ref.read(locationControllerProvider);
    if (me == null) {
      state = state.copyWith(
        loading: false,
        clearLoadingProvider: true,
        error: 'no_gps',
      );
      return;
    }
    try {
      final result = provider == RoutingProvider.neshan
          ? await _neshan.route(me.latLng, target)
          : await _osrm.route(me.latLng, target);
      if (result == null) {
        state = state.copyWith(
          loading: false,
          clearLoadingProvider: true,
          error: 'routing_failed',
        );
      } else {
        final colored = _withProfileOrTrafficColors(result, provider);
        state = state.copyWith(
          loading: false,
          clearLoadingProvider: true,
          route: colored,
          provider: provider,
          clearError: true,
        );
        _publish(colored);
      }
    } catch (e, st) {
      AppLogger.d('route failed ($provider)', e, st);
      state = state.copyWith(
        loading: false,
        clearLoadingProvider: true,
        error: 'routing_failed',
      );
    }
  }

  RouteResult _withProfileOrTrafficColors(
    RouteResult result,
    RoutingProvider provider,
  ) {
    final profile = ref.read(profileProvider).valueOrNull;
    final trip = ref.read(tripControllerProvider);
    String color = profile?.avatarColor ?? '#2563EB';
    if (profile?.avatarColor == null) {
      for (final m in trip.members) {
        if (m.id == trip.clientId && m.avatarColor != null) {
          color = m.avatarColor!;
          break;
        }
      }
    }

    // Neshan with traffic-style segments → keep those colors.
    if (provider == RoutingProvider.neshan && result.segments.isNotEmpty) {
      return result;
    }
    // Otherwise one solid line in the driver's profile color.
    return RouteResult(
      points: result.points,
      distanceMeters: result.distanceMeters,
      durationSeconds: result.durationSeconds,
      segments: [
        RouteSegment(points: result.points, colorHex: color),
      ],
    );
  }

  void _publish(RouteResult route) {
    final trip = ref.read(tripControllerProvider);
    final id = trip.clientId;
    if (id == null || route.points.length < 2) return;
    // Dense polyline only — sparse traffic segments look jagged for peers.
    final points = _downsample(route.points, 200);
    final color = route.segments.isNotEmpty
        ? route.segments.first.colorHex
        : (ref.read(profileProvider).valueOrNull?.avatarColor ?? '#2563EB');
    ref.read(tripControllerProvider.notifier).publishRoute(
          SharedRoute(
            clientId: id,
            colorHex: color,
            points: points,
          ),
        );
  }

  List<LatLng> _downsample(List<LatLng> pts, int max) {
    if (pts.length <= max) return pts;
    final out = <LatLng>[pts.first];
    final step = (pts.length - 1) / (max - 1);
    for (var i = 1; i < max - 1; i++) {
      out.add(pts[(i * step).round()]);
    }
    out.add(pts.last);
    return out;
  }

  void startNavigation() {
    if (state.route == null || state.target == null) return;
    state = state.copyWith(phase: NavPhase.navigating);
  }

  /// Follow another member's published route on this device.
  void joinSharedRoute(SharedRoute shared) {
    if (shared.points.length < 2) return;
    final segments = shared.segments.isNotEmpty
        ? shared.segments
            .map(
              (s) => RouteSegment(points: s.points, colorHex: s.colorHex),
            )
            .toList()
        : [
            RouteSegment(points: shared.points, colorHex: shared.colorHex),
          ];
    // Rough length from polyline (good enough for ETA strip).
    var meters = 0.0;
    for (var i = 1; i < shared.points.length; i++) {
      meters += haversineMeters(shared.points[i - 1], shared.points[i]);
    }
    state = state.copyWith(
      route: RouteResult(
        points: shared.points,
        distanceMeters: meters,
        durationSeconds: meters / 11.0, // ~40 km/h guess
        segments: segments,
      ),
      target: shared.points.last,
      phase: NavPhase.preview,
      clearError: true,
      clearProvider: true,
      loading: false,
      clearLoadingProvider: true,
    );
  }

  void stopNavigation() {
    state = state.copyWith(
      phase: NavPhase.idle,
      clearRoute: true,
      clearTarget: true,
      clearError: true,
      clearProvider: true,
      clearLoadingProvider: true,
      loading: false,
    );
    ref.read(tripControllerProvider.notifier).clearPublishedRoute();
  }

  void clearPreviewKeepMark() {
    state = state.copyWith(
      phase: NavPhase.idle,
      clearRoute: true,
      clearTarget: true,
      clearError: true,
      clearProvider: true,
      clearLoadingProvider: true,
      loading: false,
    );
    ref.read(tripControllerProvider.notifier).clearPublishedRoute();
  }

  Future<String?> reverseLabel(double lat, double lon) =>
      _geocoding.reverse(lat, lon);
}

final routeControllerProvider = NotifierProvider<RouteController, RouteUiState>(
  RouteController.new,
);
