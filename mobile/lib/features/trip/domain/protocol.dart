import '../../../core/constants/app_constants.dart';
import '../../../core/utils/geo.dart';
import '../../members/domain/member.dart';

class ProtocolMessage {
  ProtocolMessage({
    required this.type,
    required this.timestamp,
    required this.raw,
  });

  final String type;
  final int timestamp;
  final Map<String, dynamic> raw;

  static ProtocolMessage? tryParse(Map<String, dynamic> json) {
    final type = json['type'];
    final version = json['version'];
    final timestamp = json['timestamp'];
    if (type is! String) return null;
    if (version != AppConstants.protocolVersion) return null;
    if (timestamp is! num) return null;
    return ProtocolMessage(type: type, timestamp: timestamp.toInt(), raw: json);
  }
}

Map<String, dynamic> buildJoin({
  required String clientId,
  required String displayName,
  required String inviteCode,
  String? carName,
  String? avatarColor,
  String? leaderToken,
}) {
  return {
    'type': 'join',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'clientId': clientId,
    'displayName': displayName,
    'inviteCode': inviteCode,
    if (carName != null && carName.isNotEmpty) 'carName': carName,
    if (avatarColor != null) 'avatarColor': avatarColor,
    if (leaderToken != null) 'leaderToken': leaderToken,
  };
}

Map<String, dynamic> buildLocationUpdate({
  required double latitude,
  required double longitude,
  double? accuracy,
  double? speed,
  double? heading,
  int? timestamp,
}) {
  if (!isValidLatLng(latitude, longitude)) {
    throw ArgumentError('invalid coordinates');
  }
  return {
    'type': 'location_update',
    'version': AppConstants.protocolVersion,
    'timestamp': timestamp ?? DateTime.now().millisecondsSinceEpoch,
    'latitude': latitude,
    'longitude': longitude,
    if (accuracy != null) 'accuracy': accuracy,
    if (speed != null) 'speed': speed,
    if (heading != null) 'heading': heading,
  };
}

Map<String, dynamic> buildDestinationUpdate({
  required double latitude,
  required double longitude,
  required String leaderToken,
  String? label,
  int? ifUpdatedAt,
}) {
  return {
    'type': 'destination_update',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'latitude': latitude,
    'longitude': longitude,
    'leaderToken': leaderToken,
    if (label != null) 'label': label,
    if (ifUpdatedAt != null) 'ifUpdatedAt': ifUpdatedAt,
  };
}

Map<String, dynamic> buildDestinationClear({
  required String leaderToken,
  int? ifUpdatedAt,
}) {
  return {
    'type': 'destination_clear',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'leaderToken': leaderToken,
    if (ifUpdatedAt != null) 'ifUpdatedAt': ifUpdatedAt,
  };
}

Map<String, dynamic> buildChatMessage({
  required String text,
}) {
  return {
    'type': 'chat_message',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'text': text,
  };
}

Map<String, dynamic> buildMapMark({
  required double latitude,
  required double longitude,
  required String color,
}) {
  return {
    'type': 'map_mark',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'latitude': latitude,
    'longitude': longitude,
    'color': color,
  };
}

Map<String, dynamic> buildMapMarkClear() {
  return {
    'type': 'map_mark_clear',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
  };
}

Map<String, dynamic> buildRouteUpdate(SharedRoute route) {
  return {
    'type': 'route_update',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    ...route.toJson(),
  };
}

Map<String, dynamic> buildRouteClear() {
  return {
    'type': 'route_clear',
    'version': AppConstants.protocolVersion,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
  };
}
