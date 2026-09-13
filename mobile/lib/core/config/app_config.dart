import 'dart:convert';

import 'package:flutter/foundation.dart';

class IceServerConfig {
  const IceServerConfig({required this.urls, this.username, this.credential});

  final List<String> urls;
  final String? username;
  final String? credential;

  Map<String, dynamic> toMap() => {
    'urls': urls,
    if (username != null) 'username': username,
    if (credential != null) 'credential': credential,
  };
}

/// Central environment configuration. Prefer `--dart-define` over scattered URLs.
class AppConfig {
  const AppConfig({
    required this.apiBaseUrl,
    required this.wsBaseUrl,
    required this.mapStyleUrl,
    required this.routingBaseUrl,
    required this.geocodingBaseUrl,
    required this.trafficTileUrl,
    required this.neshanApiKey,
    required this.iceServers,
    required this.mockMode,
  });

  final String apiBaseUrl;
  final String wsBaseUrl;
  final String mapStyleUrl;
  final String routingBaseUrl;
  final String geocodingBaseUrl;
  final String trafficTileUrl;

  /// Neshan Routing API key (`Api-Key` header). Pass via `--dart-define=NESHAN_API_KEY=...`.
  final String neshanApiKey;
  final List<IceServerConfig> iceServers;
  final bool mockMode;

  static const _defaultIce =
      '['
      '{"urls":["stun:stun.l.google.com:19302"]},'
      '{"urls":["turn:openrelay.metered.ca:80","turn:openrelay.metered.ca:443"],'
      '"username":"openrelayproject","credential":"openrelayproject"},'
      '{"urls":["turns:openrelay.metered.ca:443"],'
      '"username":"openrelayproject","credential":"openrelayproject"}'
      ']';

  static AppConfig get current {
    const api = String.fromEnvironment(
      'API_BASE_URL',
      defaultValue: 'https://caravan-backend.ithenoa.workers.dev',
    );
    const ws = String.fromEnvironment(
      'WS_BASE_URL',
      defaultValue: 'wss://caravan-backend.ithenoa.workers.dev',
    );
    const mapStyle = String.fromEnvironment(
      'MAP_STYLE_URL',
      defaultValue: 'https://tiles.openfreemap.org/styles/liberty',
    );
    const routing = String.fromEnvironment(
      'ROUTING_BASE_URL',
      defaultValue: 'https://router.project-osrm.org',
    );
    const geocoding = String.fromEnvironment(
      'GEOCODING_BASE_URL',
      defaultValue: 'https://nominatim.openstreetmap.org',
    );
    const traffic = String.fromEnvironment(
      'TRAFFIC_TILE_URL',
      defaultValue: '',
    );
    const neshanApiKey = String.fromEnvironment(
      'NESHAN_API_KEY',
      defaultValue: '',
    );
    const iceJson = String.fromEnvironment(
      'ICE_SERVERS',
      defaultValue: _defaultIce,
    );
    const mockDefine = bool.fromEnvironment('MOCK_MODE', defaultValue: false);

    return AppConfig(
      apiBaseUrl: api,
      wsBaseUrl: ws,
      mapStyleUrl: mapStyle,
      routingBaseUrl: routing,
      geocodingBaseUrl: geocoding,
      trafficTileUrl: traffic,
      neshanApiKey: neshanApiKey,
      iceServers: _parseIce(iceJson),
      mockMode: !kReleaseMode && mockDefine,
    );
  }

  static List<IceServerConfig> _parseIce(String json) {
    try {
      final raw = jsonDecode(json) as List<dynamic>;
      return raw.map((e) {
        final m = e as Map<String, dynamic>;
        final urls = m['urls'];
        return IceServerConfig(
          urls: urls is String
              ? [urls]
              : (urls as List<dynamic>).cast<String>(),
          username: m['username'] as String?,
          credential: m['credential'] as String?,
        );
      }).toList();
    } catch (_) {
      return const [
        IceServerConfig(urls: ['stun:stun.l.google.com:19302']),
      ];
    }
  }
}
