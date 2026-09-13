import 'dart:async';
import 'dart:math';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/app_config.dart';
import '../../../core/utils/geo.dart';
import '../../members/domain/member.dart';
import '../../trip/data/trip_controller.dart';

/// Debug-only simulated members orbiting the user.
class MockFleetController extends Notifier<List<TripMember>> {
  Timer? _timer;
  final _rand = Random(42);

  @override
  List<TripMember> build() {
    ref.onDispose(() => _timer?.cancel());
    if (AppConfig.current.mockMode) {
      _timer = Timer.periodic(const Duration(seconds: 2), (_) => _tick());
    }
    return const [];
  }

  void _tick() {
    final trip = ref.read(tripControllerProvider);
    if (!trip.joined) return;
    const base = LatLng(35.6892, 51.389);
    final members = List.generate(3, (i) {
      final t = DateTime.now().millisecondsSinceEpoch / 1000 + i * 10;
      final lat = base.latitude + 0.002 * sin(t / 20 + i);
      final lng = base.longitude + 0.002 * cos(t / 20 + i);
      return TripMember(
        id: 'mock-$i',
        displayName: 'Sim ${i + 1}',
        carName: 'Car ${i + 1}',
        avatarColor:
            '#${(0x100000 + _rand.nextInt(0xEFFFFF)).toRadixString(16)}',
        latitude: lat,
        longitude: lng,
        speed: 8,
        heading: (t * 10) % 360,
        lastLocationAt: DateTime.now(),
        lastSeenAt: DateTime.now(),
        connectionStatus: MemberConnectionStatus.connected,
        isLeader: false,
      );
    });
    state = members;
    // Merge into trip view by replacing mock ids in a side channel is complex;
    // expose via provider for map to optionally overlay.
  }
}

final mockFleetProvider =
    NotifierProvider<MockFleetController, List<TripMember>>(
      MockFleetController.new,
    );
