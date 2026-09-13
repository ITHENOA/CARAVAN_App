import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/app_config.dart';
import '../../../l10n/app_localizations.dart';
import '../../location/data/location_controller.dart';
import '../../trip/data/trip_controller.dart';
import '../../voice/data/voice_controller.dart';

class DebugScreen extends ConsumerWidget {
  const DebugScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final cfg = AppConfig.current;
    final trip = ref.watch(tripControllerProvider);
    final loc = ref.watch(locationControllerProvider);
    final voice = ref.watch(voiceControllerProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.debugDiagnostics)),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _row('API', cfg.apiBaseUrl),
          _row('WS', cfg.wsBaseUrl),
          _row('Map style', cfg.mapStyleUrl),
          _row('Routing', cfg.routingBaseUrl),
          _row('Neshan key', cfg.neshanApiKey.isEmpty ? 'missing' : 'set'),
          _row('Mock', '${cfg.mockMode}'),
          _row('WS state', trip.connection.name),
          _row('Trip ID', trip.tripId ?? '—'),
          _row('Client ID', trip.clientId ?? '—'),
          _row('Leader ID', trip.leaderId ?? '—'),
          _row(
            'Last GPS',
            loc == null
                ? '—'
                : '${loc.latitude.toStringAsFixed(5)}, ${loc.longitude.toStringAsFixed(5)} @ ${loc.timestamp.toIso8601String()}',
          ),
          _row(
            'Last server packet',
            trip.lastServerPacketAt?.toIso8601String() ?? '—',
          ),
          _row('PTT UI', voice.ui.name),
          _row('Peers', voice.peerStates.toString()),
        ],
      ),
    );
  }

  Widget _row(String k, String v) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(k, style: const TextStyle(fontWeight: FontWeight.w700)),
          SelectableText(v),
        ],
      ),
    );
  }
}
