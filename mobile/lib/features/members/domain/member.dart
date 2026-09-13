import 'package:equatable/equatable.dart';

import '../../../core/utils/geo.dart';

enum MemberConnectionStatus { connected, reconnecting, offline }

class TripMember extends Equatable {
  const TripMember({
    required this.id,
    required this.displayName,
    this.carName,
    this.avatarColor,
    this.latitude,
    this.longitude,
    this.accuracy,
    this.speed,
    this.heading,
    this.lastLocationAt,
    required this.lastSeenAt,
    required this.connectionStatus,
    required this.isLeader,
  });

  final String id;
  final String displayName;
  final String? carName;
  final String? avatarColor;
  final double? latitude;
  final double? longitude;
  final double? accuracy;
  final double? speed;
  final double? heading;
  final DateTime? lastLocationAt;
  final DateTime lastSeenAt;
  final MemberConnectionStatus connectionStatus;
  final bool isLeader;

  LatLng? get position => latitude != null && longitude != null
      ? LatLng(latitude!, longitude!)
      : null;

  bool get isStale {
    if (lastLocationAt == null) return true;
    return DateTime.now().difference(lastLocationAt!).inSeconds > 15;
  }

  TripMember copyWith({
    String? displayName,
    String? carName,
    String? avatarColor,
    double? latitude,
    double? longitude,
    double? accuracy,
    double? speed,
    double? heading,
    DateTime? lastLocationAt,
    DateTime? lastSeenAt,
    MemberConnectionStatus? connectionStatus,
    bool? isLeader,
  }) {
    return TripMember(
      id: id,
      displayName: displayName ?? this.displayName,
      carName: carName ?? this.carName,
      avatarColor: avatarColor ?? this.avatarColor,
      latitude: latitude ?? this.latitude,
      longitude: longitude ?? this.longitude,
      accuracy: accuracy ?? this.accuracy,
      speed: speed ?? this.speed,
      heading: heading ?? this.heading,
      lastLocationAt: lastLocationAt ?? this.lastLocationAt,
      lastSeenAt: lastSeenAt ?? this.lastSeenAt,
      connectionStatus: connectionStatus ?? this.connectionStatus,
      isLeader: isLeader ?? this.isLeader,
    );
  }

  factory TripMember.fromJson(Map<String, dynamic> json) {
    return TripMember(
      id: json['id'] as String,
      displayName: json['displayName'] as String? ?? 'Member',
      carName: json['carName'] as String?,
      avatarColor: json['avatarColor'] as String?,
      latitude: (json['latitude'] as num?)?.toDouble(),
      longitude: (json['longitude'] as num?)?.toDouble(),
      accuracy: (json['accuracy'] as num?)?.toDouble(),
      speed: (json['speed'] as num?)?.toDouble(),
      heading: (json['heading'] as num?)?.toDouble(),
      lastLocationAt: json['lastLocationAt'] != null
          ? DateTime.fromMillisecondsSinceEpoch(json['lastLocationAt'] as int)
          : null,
      lastSeenAt: DateTime.fromMillisecondsSinceEpoch(
        (json['lastSeenAt'] as int?) ?? DateTime.now().millisecondsSinceEpoch,
      ),
      connectionStatus: _status(json['connectionStatus'] as String?),
      isLeader: json['isLeader'] as bool? ?? false,
    );
  }

  static MemberConnectionStatus _status(String? s) {
    switch (s) {
      case 'reconnecting':
        return MemberConnectionStatus.reconnecting;
      case 'offline':
        return MemberConnectionStatus.offline;
      default:
        return MemberConnectionStatus.connected;
    }
  }

  @override
  List<Object?> get props => [
    id,
    latitude,
    longitude,
    lastLocationAt,
    isLeader,
  ];
}

class TripDestination extends Equatable {
  const TripDestination({
    required this.latitude,
    required this.longitude,
    this.label,
    required this.updatedAt,
    required this.updatedById,
    required this.updatedByName,
  });

  final double latitude;
  final double longitude;
  final String? label;
  final DateTime updatedAt;
  final String updatedById;
  final String updatedByName;

  LatLng get position => LatLng(latitude, longitude);

  factory TripDestination.fromJson(Map<String, dynamic> json) {
    return TripDestination(
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      label: json['label'] as String?,
      updatedAt: DateTime.fromMillisecondsSinceEpoch(json['updatedAt'] as int),
      updatedById: json['updatedById'] as String? ?? '',
      updatedByName: json['updatedByName'] as String? ?? '',
    );
  }

  @override
  List<Object?> get props => [latitude, longitude, updatedAt];
}

class MapMark extends Equatable {
  const MapMark({
    required this.clientId,
    required this.displayName,
    required this.latitude,
    required this.longitude,
    required this.color,
    required this.updatedAt,
  });

  final String clientId;
  final String displayName;
  final double latitude;
  final double longitude;
  final String color;
  final DateTime updatedAt;

  LatLng get position => LatLng(latitude, longitude);

  factory MapMark.fromJson(Map<String, dynamic> json) {
    return MapMark(
      clientId: json['clientId'] as String? ?? '',
      displayName: json['displayName'] as String? ?? 'Driver',
      latitude: (json['latitude'] as num).toDouble(),
      longitude: (json['longitude'] as num).toDouble(),
      color: json['color'] as String? ?? '#0EA5E9',
      updatedAt: DateTime.fromMillisecondsSinceEpoch(
        (json['updatedAt'] as int?) ?? DateTime.now().millisecondsSinceEpoch,
      ),
    );
  }

  @override
  List<Object?> get props => [clientId, latitude, longitude, updatedAt];
}

/// Shared route polyline broadcast to other trip members.
class SharedRoute extends Equatable {
  const SharedRoute({
    required this.clientId,
    required this.colorHex,
    required this.points,
    this.segments = const [],
  });

  final String clientId;
  final String colorHex;
  final List<LatLng> points;
  final List<({String colorHex, List<LatLng> points})> segments;

  factory SharedRoute.fromJson(Map<String, dynamic> json) {
    List<LatLng> parsePts(dynamic raw) {
      if (raw is! List) return const [];
      final out = <LatLng>[];
      for (final e in raw) {
        if (e is List && e.length >= 2) {
          out.add(LatLng((e[0] as num).toDouble(), (e[1] as num).toDouble()));
        } else if (e is Map) {
          final lat = e['lat'] ?? e['latitude'];
          final lng = e['lng'] ?? e['longitude'];
          if (lat is num && lng is num) {
            out.add(LatLng(lat.toDouble(), lng.toDouble()));
          }
        }
      }
      return out;
    }

    final segs = <({String colorHex, List<LatLng> points})>[];
    final rawSegs = json['segments'] as List<dynamic>? ?? [];
    for (final s in rawSegs) {
      if (s is! Map) continue;
      segs.add((
        colorHex: s['colorHex'] as String? ?? '#2563EB',
        points: parsePts(s['points']),
      ));
    }

    return SharedRoute(
      clientId: json['clientId'] as String? ?? '',
      colorHex: json['colorHex'] as String? ?? '#2563EB',
      points: parsePts(json['points']),
      segments: segs,
    );
  }

  Map<String, dynamic> toJson() => {
        'clientId': clientId,
        'colorHex': colorHex,
        'points': points.map((p) => [p.latitude, p.longitude]).toList(),
        if (segments.isNotEmpty)
          'segments': segments
              .map(
                (s) => {
                  'colorHex': s.colorHex,
                  'points':
                      s.points.map((p) => [p.latitude, p.longitude]).toList(),
                },
              )
              .toList(),
      };

  @override
  List<Object?> get props => [clientId, colorHex, points.length, segments.length];
}
