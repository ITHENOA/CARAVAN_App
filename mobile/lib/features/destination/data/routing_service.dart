import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../../core/config/app_config.dart';
import '../../../core/utils/geo.dart';

/// One colored stretch of a route (used for Neshan speed-based traffic tint).
class RouteSegment {
  const RouteSegment({
    required this.points,
    required this.colorHex,
  });

  final List<LatLng> points;
  final String colorHex;
}

class RouteResult {
  const RouteResult({
    required this.points,
    required this.distanceMeters,
    required this.durationSeconds,
    this.segments = const [],
  });

  final List<LatLng> points;
  final double distanceMeters;
  final double durationSeconds;

  /// Optional per-stretch colors. Empty → render [points] as a single blue line.
  final List<RouteSegment> segments;

  String get etaLabel {
    final mins = (durationSeconds / 60).round();
    if (mins < 60) return '$mins min';
    final h = mins ~/ 60;
    final m = mins % 60;
    return m == 0 ? '${h}h' : '${h}h ${m}m';
  }

  String distanceLabel({bool metric = true}) =>
      formatDistanceMeters(distanceMeters, metric: metric);
}

class RoutingService {
  RoutingService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  Future<RouteResult?> route(LatLng from, LatLng to) async {
    final base = AppConfig.current.routingBaseUrl.replaceAll(RegExp(r'/$'), '');
    if (base.isEmpty) return null;
    final path =
        '$base/route/v1/driving/${from.longitude},${from.latitude};'
        '${to.longitude},${to.latitude}';
    final uri = Uri.parse(path).replace(
      queryParameters: {'overview': 'full', 'geometries': 'geojson'},
    );
    final res = await _client.get(uri);
    if (res.statusCode != 200) return null;
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (body['code'] != 'Ok') return null;
    final routes = body['routes'] as List<dynamic>?;
    if (routes == null || routes.isEmpty) return null;
    final route = routes.first as Map<String, dynamic>;
    final geometry = route['geometry'] as Map<String, dynamic>;
    final coords = geometry['coordinates'] as List<dynamic>;
    final points = coords.map((c) {
      final pair = c as List<dynamic>;
      return LatLng((pair[1] as num).toDouble(), (pair[0] as num).toDouble());
    }).toList();
    return RouteResult(
      points: points,
      distanceMeters: (route['distance'] as num).toDouble(),
      durationSeconds: (route['duration'] as num).toDouble(),
    );
  }

  /// Pure parser for tests.
  static RouteResult? parseOsrmBody(String body) {
    final json = jsonDecode(body) as Map<String, dynamic>;
    if (json['code'] != 'Ok') return null;
    final routes = json['routes'] as List<dynamic>?;
    if (routes == null || routes.isEmpty) return null;
    final route = routes.first as Map<String, dynamic>;
    final geometry = route['geometry'] as Map<String, dynamic>;
    final coords = geometry['coordinates'] as List<dynamic>;
    final points = coords.map((c) {
      final pair = c as List<dynamic>;
      return LatLng((pair[1] as num).toDouble(), (pair[0] as num).toDouble());
    }).toList();
    return RouteResult(
      points: points,
      distanceMeters: (route['distance'] as num).toDouble(),
      durationSeconds: (route['duration'] as num).toDouble(),
    );
  }
}
