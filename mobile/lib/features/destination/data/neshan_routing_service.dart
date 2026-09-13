import 'dart:convert';
import 'dart:math' as math;

import 'package:http/http.dart' as http;

import '../../../core/config/app_config.dart';
import '../../../core/utils/convoy_colors.dart';
import '../../../core/utils/geo.dart';
import 'routing_service.dart';

/// Traffic-aware routing via [Neshan Direction API v4](https://platform.neshan.org/docs/api/routing-category/routing/).
class NeshanRoutingService {
  NeshanRoutingService({http.Client? client})
    : _client = client ?? http.Client();

  static const _defaultBase = 'https://api.neshan.org';
  static const _timeout = Duration(seconds: 20);

  final http.Client _client;

  Future<RouteResult?> route(LatLng from, LatLng to) async {
    final key = AppConfig.current.neshanApiKey.trim();
    if (key.isEmpty) return null;

    final uri = Uri.parse('$_defaultBase/v4/direction').replace(
      queryParameters: {
        'type': 'car',
        'origin': '${from.latitude},${from.longitude}',
        'destination': '${to.latitude},${to.longitude}',
      },
    );

    final res = await _client
        .get(uri, headers: {'Api-Key': key})
        .timeout(_timeout);
    if (res.statusCode != 200) return null;
    return parseNeshanBody(res.body);
  }

  /// Pure parser for tests. Expects Neshan v4 direction JSON.
  ///
  /// Neshan does not return congestion labels. Per-step `distance`/`duration`
  /// imply speed; we tint segments with [convoySpeedColorHex] from that.
  static RouteResult? parseNeshanBody(String body) {
    final dynamic decoded;
    try {
      decoded = jsonDecode(body);
    } catch (_) {
      return null;
    }
    if (decoded is! Map<String, dynamic>) return null;

    final routes = decoded['routes'] as List<dynamic>?;
    if (routes == null || routes.isEmpty) return null;
    final route = routes.first;
    if (route is! Map<String, dynamic>) return null;

    var distanceMeters = 0.0;
    var durationSeconds = 0.0;
    final builtSegments = <RouteSegment>[];

    final legs = route['legs'] as List<dynamic>?;
    if (legs != null) {
      for (final leg in legs) {
        if (leg is! Map<String, dynamic>) continue;
        final distance = leg['distance'];
        final duration = leg['duration'];
        if (distance is Map<String, dynamic>) {
          final value = distance['value'];
          if (value is num) distanceMeters += value.toDouble();
        }
        if (duration is Map<String, dynamic>) {
          final value = duration['value'];
          if (value is num) durationSeconds += value.toDouble();
        }

        final steps = leg['steps'] as List<dynamic>?;
        if (steps == null) continue;
        for (final step in steps) {
          if (step is! Map<String, dynamic>) continue;
          final poly = step['polyline'];
          if (poly is! String || poly.isEmpty) continue;
          final pts = decodePolyline(poly);
          if (pts.length < 2) continue;

          final stepDist = _numValue(step['distance']);
          final stepDur = _numValue(step['duration']);
          final speedMps = (stepDur > 0 && stepDist > 0)
              ? stepDist / stepDur
              : double.nan;
          final color = convoySpeedColorHex(
            speedMps.isNaN ? null : speedMps,
          );

          if (builtSegments.isNotEmpty &&
              builtSegments.last.colorHex == color) {
            final merged = List<LatLng>.from(builtSegments.last.points);
            final start = pts.first;
            final last = merged.last;
            final skipFirst =
                last.latitude == start.latitude &&
                last.longitude == start.longitude;
            merged.addAll(skipFirst ? pts.skip(1) : pts);
            builtSegments[builtSegments.length - 1] = RouteSegment(
              points: merged,
              colorHex: color,
            );
          } else {
            builtSegments.add(RouteSegment(points: pts, colorHex: color));
          }
        }
      }
    }

    List<LatLng> points;
    if (builtSegments.isNotEmpty) {
      points = <LatLng>[];
      for (final seg in builtSegments) {
        if (points.isEmpty) {
          points.addAll(seg.points);
        } else {
          final start = seg.points.first;
          final last = points.last;
          final skipFirst =
              last.latitude == start.latitude &&
              last.longitude == start.longitude;
          points.addAll(skipFirst ? seg.points.skip(1) : seg.points);
        }
      }
    } else {
      final overview = route['overview_polyline'];
      if (overview is! Map<String, dynamic>) return null;
      final encoded = overview['points'];
      if (encoded is! String || encoded.isEmpty) return null;
      points = decodePolyline(encoded);
    }

    if (points.length < 2) return null;

    return RouteResult(
      points: points,
      distanceMeters: distanceMeters,
      durationSeconds: durationSeconds,
      segments: builtSegments,
    );
  }

  static double _numValue(dynamic field) {
    if (field is Map<String, dynamic>) {
      final value = field['value'];
      if (value is num) return value.toDouble();
    }
    return 0;
  }

  /// Google Encoded Polyline Algorithm (precision 5), as used by Neshan.
  static List<LatLng> decodePolyline(String encoded, {int precision = 5}) {
    final coordinates = <LatLng>[];
    var index = 0;
    var lat = 0;
    var lng = 0;
    final factor = math.pow(10, precision).toDouble();

    while (index < encoded.length) {
      var result = 0;
      var shift = 0;
      int b;
      do {
        if (index >= encoded.length) return coordinates;
        b = encoded.codeUnitAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      final dlat = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
      lat += dlat;

      result = 0;
      shift = 0;
      do {
        if (index >= encoded.length) return coordinates;
        b = encoded.codeUnitAt(index++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      final dlng = (result & 1) != 0 ? ~(result >> 1) : (result >> 1);
      lng += dlng;

      coordinates.add(LatLng(lat / factor, lng / factor));
    }
    return coordinates;
  }
}
