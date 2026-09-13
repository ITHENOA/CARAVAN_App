import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:uuid/uuid.dart';

class UserProfile {
  const UserProfile({
    required this.clientId,
    required this.displayName,
    this.carName,
    this.avatarColor = '#2E7D32',
  });

  final String clientId;
  final String displayName;
  final String? carName;
  final String avatarColor;

  bool get isComplete => displayName.trim().isNotEmpty;
}

class ProfileRepository {
  ProfileRepository(this._prefs);
  final SharedPreferences _prefs;
  static const _kClient = 'client_id';
  static const _kName = 'display_name';
  static const _kCar = 'car_name';
  static const _kColor = 'avatar_color';
  static const _kLocale = 'locale';
  static const _kTheme = 'theme_mode';
  static const _kUnits = 'units_metric';
  static const _kVoice = 'voice_enabled';

  Future<UserProfile> load() async {
    var clientId = _prefs.getString(_kClient);
    if (clientId == null || clientId.isEmpty) {
      clientId = const Uuid().v4();
      await _prefs.setString(_kClient, clientId);
    }
    return UserProfile(
      clientId: clientId,
      displayName: _prefs.getString(_kName) ?? '',
      carName: _prefs.getString(_kCar),
      avatarColor: _prefs.getString(_kColor) ?? '#2E7D32',
    );
  }

  Future<void> save(UserProfile profile) async {
    await _prefs.setString(_kClient, profile.clientId);
    await _prefs.setString(_kName, profile.displayName.trim());
    if (profile.carName != null) {
      await _prefs.setString(_kCar, profile.carName!);
    }
    await _prefs.setString(_kColor, profile.avatarColor);
  }

  String? get localeCode => _prefs.getString(_kLocale);
  Future<void> setLocaleCode(String? code) async {
    if (code == null) {
      await _prefs.remove(_kLocale);
    } else {
      await _prefs.setString(_kLocale, code);
    }
  }

  String get themeMode => _prefs.getString(_kTheme) ?? 'system';
  Future<void> setThemeMode(String mode) => _prefs.setString(_kTheme, mode);

  bool get metricUnits => _prefs.getBool(_kUnits) ?? true;
  Future<void> setMetricUnits(bool v) => _prefs.setBool(_kUnits, v);

  bool get voiceEnabled => _prefs.getBool(_kVoice) ?? true;
  Future<void> setVoiceEnabled(bool v) => _prefs.setBool(_kVoice, v);

  static const _kLastTripId = 'last_trip_id';
  static const _kLastTripName = 'last_trip_name';
  static const _kLastTripInvite = 'last_trip_invite';
  static const _kLastTripLeaderToken = 'last_trip_leader_token';

  Map<String, String>? getLastTrip() {
    final id = _prefs.getString(_kLastTripId);
    final invite = _prefs.getString(_kLastTripInvite);
    if (id == null || invite == null) return null;
    return {
      'tripId': id,
      'inviteCode': invite,
      'tripName': _prefs.getString(_kLastTripName) ?? '',
      'leaderToken': _prefs.getString(_kLastTripLeaderToken) ?? '',
    };
  }

  Future<void> saveLastTrip({
    required String tripId,
    required String inviteCode,
    required String tripName,
    String? leaderToken,
  }) async {
    await _prefs.setString(_kLastTripId, tripId);
    await _prefs.setString(_kLastTripInvite, inviteCode);
    await _prefs.setString(_kLastTripName, tripName);
    if (leaderToken != null && leaderToken.isNotEmpty) {
      await _prefs.setString(_kLastTripLeaderToken, leaderToken);
    } else {
      await _prefs.remove(_kLastTripLeaderToken);
    }
  }

  Future<void> clearLastTrip() async {
    await _prefs.remove(_kLastTripId);
    await _prefs.remove(_kLastTripInvite);
    await _prefs.remove(_kLastTripName);
    await _prefs.remove(_kLastTripLeaderToken);
  }
}

final sharedPreferencesProvider = Provider<SharedPreferences>((ref) {
  throw UnimplementedError('override in main');
});

final profileRepositoryProvider = Provider<ProfileRepository>((ref) {
  return ProfileRepository(ref.watch(sharedPreferencesProvider));
});

final profileProvider = AsyncNotifierProvider<ProfileNotifier, UserProfile>(
  ProfileNotifier.new,
);

class ProfileNotifier extends AsyncNotifier<UserProfile> {
  @override
  Future<UserProfile> build() {
    return ref.watch(profileRepositoryProvider).load();
  }

  Future<void> save(UserProfile profile) async {
    await ref.read(profileRepositoryProvider).save(profile);
    state = AsyncData(profile);
  }
}
