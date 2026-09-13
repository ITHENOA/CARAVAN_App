import 'dart:async';

import 'package:flutter_compass/flutter_compass.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:geolocator/geolocator.dart';

import '../../../core/constants/app_constants.dart';
import '../../../core/utils/app_logger.dart';
import '../../../core/utils/geo.dart';
import '../../trip/data/trip_controller.dart';

class DeviceLocation {
  const DeviceLocation({
    required this.latitude,
    required this.longitude,
    this.accuracy,
    this.speed,
    this.heading,
    required this.timestamp,
  });

  final double latitude;
  final double longitude;
  final double? accuracy;
  final double? speed;
  final double? heading;
  final DateTime timestamp;

  LatLng get latLng => LatLng(latitude, longitude);
}

class LocationController extends Notifier<DeviceLocation?> {
  StreamSubscription<Position>? _sub;
  StreamSubscription<CompassEvent>? _compassSub;
  DeviceLocation? _lastSent;
  double? _compassHeading;
  double? _lastGoodHeading;

  @override
  DeviceLocation? build() {
    ref.onDispose(() {
      unawaited(_sub?.cancel());
      unawaited(_compassSub?.cancel());
    });
    return null;
  }

  Future<bool> ensurePermission() async {
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) {
      permission = await Geolocator.requestPermission();
    }
    if (permission == LocationPermission.denied ||
        permission == LocationPermission.deniedForever) {
      return false;
    }
    final enabled = await Geolocator.isLocationServiceEnabled();
    return enabled;
  }

  Future<void> start() async {
    final ok = await ensurePermission();
    if (!ok) {
      AppLogger.d('Location permission denied');
      return;
    }
    await _sub?.cancel();
    await _compassSub?.cancel();

    _compassSub = FlutterCompass.events?.listen((event) {
      final h = event.heading;
      if (h == null || h.isNaN) return;
      _compassHeading = (h + 360) % 360;
      final cur = state;
      if (cur == null) return;
      // When slow/stopped, GPS course is unreliable — prefer compass for arrow.
      if ((cur.speed ?? 0) < 1.5) {
        _lastGoodHeading = _compassHeading;
        state = DeviceLocation(
          latitude: cur.latitude,
          longitude: cur.longitude,
          accuracy: cur.accuracy,
          speed: cur.speed,
          heading: _compassHeading,
          timestamp: DateTime.now(),
        );
      }
    });

    _sub = Geolocator.getPositionStream(
      locationSettings: const LocationSettings(
        accuracy: LocationAccuracy.bestForNavigation,
        distanceFilter: 1,
      ),
    ).listen(
      _onPosition,
      onError: (Object e, StackTrace st) {
        AppLogger.d('GPS stream error', e, st);
      },
    );
  }

  Future<void> stop() async {
    await _sub?.cancel();
    await _compassSub?.cancel();
    _sub = null;
    _compassSub = null;
  }

  double? _resolveHeading(Position pos) {
    final gps = pos.heading;
    final gpsOk = !gps.isNaN && gps >= 0;
    final speed = pos.speed.isNaN ? 0.0 : pos.speed;

    // Moving: prefer GPS course; stationary: prefer compass.
    if (speed >= 1.5 && gpsOk) {
      _lastGoodHeading = gps;
      return gps;
    }
    if (_compassHeading != null) {
      _lastGoodHeading = _compassHeading;
      return _compassHeading;
    }
    if (gpsOk) {
      _lastGoodHeading = gps;
      return gps;
    }
    return _lastGoodHeading;
  }

  void _onPosition(Position pos) {
    if (!isValidLatLng(pos.latitude, pos.longitude)) return;
    final loc = DeviceLocation(
      latitude: pos.latitude,
      longitude: pos.longitude,
      accuracy: pos.accuracy,
      speed: pos.speed.isNaN ? null : pos.speed,
      heading: _resolveHeading(pos),
      timestamp: pos.timestamp,
    );
    state = loc;
    _maybePublish(loc);
  }

  void _maybePublish(DeviceLocation loc) {
    final last = _lastSent;
    final moving = (loc.speed ?? 0) > 1.0;
    final minInterval = moving
        ? AppConstants.locationMovingInterval
        : AppConstants.locationStoppedInterval;
    if (last != null) {
      final dt = loc.timestamp.difference(last.timestamp);
      final dist = haversineMeters(last.latLng, loc.latLng);
      if (dt < minInterval && dist < AppConstants.meaningfulMoveMeters) {
        return;
      }
    }
    _lastSent = loc;
    ref
        .read(tripControllerProvider.notifier)
        .sendLocation(
          latitude: loc.latitude,
          longitude: loc.longitude,
          accuracy: loc.accuracy,
          speed: loc.speed,
          heading: loc.heading,
        );
  }
}

final locationControllerProvider =
    NotifierProvider<LocationController, DeviceLocation?>(
      LocationController.new,
    );
