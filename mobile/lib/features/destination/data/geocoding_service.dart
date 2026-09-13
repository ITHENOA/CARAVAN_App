import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../../core/config/app_config.dart';
import '../../../core/utils/geo.dart';

class PlaceSuggestion {
  const PlaceSuggestion({
    required this.latitude,
    required this.longitude,
    required this.label,
    this.detail,
  });

  final double latitude;
  final double longitude;
  final String label;
  final String? detail;

  LatLng get position => LatLng(latitude, longitude);
}

class GeocodingService {
  GeocodingService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const _userAgent = 'CaravanApp/1.0 (convoy companion; contact@caravan.app)';

  Future<List<PlaceSuggestion>> search(String query) async {
    final q = query.trim();
    if (q.length < 2) return const [];
    final base = AppConfig.current.geocodingBaseUrl.replaceAll(RegExp(r'/$'), '');
    final uri = Uri.parse('$base/search').replace(
      queryParameters: {
        'q': q,
        'format': 'json',
        'addressdetails': '0',
        'limit': '6',
      },
    );
    final res = await _client.get(
      uri,
      headers: {'User-Agent': _userAgent, 'Accept': 'application/json'},
    );
    if (res.statusCode != 200) return const [];
    final list = jsonDecode(res.body) as List<dynamic>;
    return list.map((raw) {
      final m = raw as Map<String, dynamic>;
      final display = (m['display_name'] as String?) ?? 'Place';
      final parts = display.split(',');
      return PlaceSuggestion(
        latitude: double.parse(m['lat'] as String),
        longitude: double.parse(m['lon'] as String),
        label: parts.first.trim(),
        detail: display,
      );
    }).toList();
  }

  Future<String?> reverse(double lat, double lon) async {
    final base = AppConfig.current.geocodingBaseUrl.replaceAll(RegExp(r'/$'), '');
    final uri = Uri.parse('$base/reverse').replace(
      queryParameters: {
        'lat': lat.toString(),
        'lon': lon.toString(),
        'format': 'json',
        'zoom': '16',
      },
    );
    final res = await _client.get(
      uri,
      headers: {'User-Agent': _userAgent, 'Accept': 'application/json'},
    );
    if (res.statusCode != 200) return null;
    final m = jsonDecode(res.body) as Map<String, dynamic>;
    final display = m['display_name'] as String?;
    if (display == null) return null;
    return display.split(',').first.trim();
  }
}
