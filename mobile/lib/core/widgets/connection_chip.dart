import 'package:flutter/material.dart';

import '../networking/ws_client.dart';
import '../../l10n/app_localizations.dart';

class ConnectionChip extends StatelessWidget {
  const ConnectionChip({super.key, required this.state});

  final WsConnectionState state;

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final (label, color) = switch (state) {
      WsConnectionState.connected => (l10n.connected, Colors.green),
      WsConnectionState.connecting ||
      WsConnectionState.reconnecting =>
        (l10n.reconnecting, Colors.orange),
      WsConnectionState.disconnected => (l10n.offline, Colors.red),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.circle, size: 10, color: color),
          const SizedBox(width: 6),
          Text(label),
        ],
      ),
    );
  }
}
