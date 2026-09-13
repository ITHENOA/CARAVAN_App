import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/app_config.dart';
import '../errors/app_exception.dart';

class CreateTripResult {
  CreateTripResult({
    required this.tripId,
    required this.inviteCode,
    required this.leaderToken,
    required this.leaderId,
    required this.name,
    required this.qrPayload,
  });

  final String tripId;
  final String inviteCode;
  final String leaderToken;
  final String leaderId;
  final String name;
  final String qrPayload;

  factory CreateTripResult.fromJson(Map<String, dynamic> json) {
    return CreateTripResult(
      tripId: json['tripId'] as String,
      inviteCode: json['inviteCode'] as String,
      leaderToken: json['leaderToken'] as String,
      leaderId: json['leaderId'] as String,
      name: json['name'] as String? ?? 'Trip',
      qrPayload: json['qrPayload'] as String? ?? '',
    );
  }
}

class TripApiClient {
  TripApiClient({http.Client? client, AppConfig? config})
    : _client = client ?? http.Client(),
      _config = config ?? AppConfig.current;

  final http.Client _client;
  final AppConfig _config;

  Future<CreateTripResult> createTrip({
    required String displayName,
    String? clientId,
    String? name,
  }) async {
    final uri = Uri.parse('${_config.apiBaseUrl}/api/trips');
    final res = await _client.post(
      uri,
      headers: {'content-type': 'application/json'},
      body: jsonEncode({
        'displayName': displayName,
        if (clientId != null) 'clientId': clientId,
        if (name != null) 'name': name,
      }),
    );
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw AppException('Unable to create trip (${res.statusCode})');
    }
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return CreateTripResult.fromJson(body);
  }

  Future<String> lookupTripId(String inviteCode) async {
    final uri = Uri.parse('${_config.apiBaseUrl}/api/trips/lookup');
    final res = await _client.post(
      uri,
      headers: {'content-type': 'application/json'},
      body: jsonEncode({'inviteCode': inviteCode}),
    );
    if (res.statusCode < 200 || res.statusCode >= 300) {
      throw AppException('Unable to join trip.');
    }
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    return body['tripId'] as String;
  }
}
