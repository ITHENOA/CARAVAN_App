import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../../core/networking/api_client.dart';
import '../../../l10n/app_localizations.dart';
import '../data/trip_controller.dart';

class JoinTripScreen extends ConsumerStatefulWidget {
  const JoinTripScreen({super.key});

  @override
  ConsumerState<JoinTripScreen> createState() => _JoinTripScreenState();
}

class _JoinTripScreenState extends ConsumerState<JoinTripScreen> {
  final _code = TextEditingController();
  final _api = TripApiClient();
  bool _scanning = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _join({String? tripId, required String code}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final id = tripId ?? await _api.lookupTripId(code);
      await ref
          .read(tripControllerProvider.notifier)
          .joinTrip(tripId: id, inviteCode: code);
      if (!mounted) return;
      context.go('/trip');
    } catch (_) {
      setState(() => _error = AppLocalizations.of(context).unableToJoin);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _joinSmart() async {
    final raw = _code.text.trim();
    if (raw.startsWith('caravan://join/')) {
      final uri = Uri.parse(raw);
      final tripId = uri.pathSegments.isNotEmpty ? uri.pathSegments.last : null;
      final code = uri.queryParameters['code'];
      if (tripId != null && code != null) {
        await _join(tripId: tripId, code: code);
        return;
      }
    }
    if (raw.contains('|')) {
      final parts = raw.split('|');
      await _join(tripId: parts[0].trim(), code: parts[1].trim());
      return;
    }
    await _join(code: raw.toUpperCase());
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    return Scaffold(
      appBar: AppBar(title: Text(l10n.joinTrip)),
      body: ListView(
        padding: const EdgeInsets.all(24),
        children: [
          TextField(
            controller: _code,
            textCapitalization: TextCapitalization.characters,
            decoration: InputDecoration(
              labelText: l10n.enterTripCode,
              border: const OutlineInputBorder(),
              hintText: '7K4-M2P',
            ),
          ),
          if (_error != null) ...[
            const SizedBox(height: 12),
            Text(
              _error!,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ],
          const SizedBox(height: 16),
          FilledButton(
            onPressed: _busy ? null : _joinSmart,
            child: _busy
                ? const SizedBox(
                    width: 22,
                    height: 22,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : Text(l10n.joinTrip),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () => setState(() => _scanning = !_scanning),
            icon: const Icon(Icons.qr_code_scanner),
            label: Text(l10n.scanQr),
          ),
          if (_scanning) ...[
            const SizedBox(height: 16),
            SizedBox(
              height: 280,
              child: MobileScanner(
                onDetect: (capture) {
                  final value = capture.barcodes.firstOrNull?.rawValue;
                  if (value == null) return;
                  setState(() {
                    _scanning = false;
                    _code.text = value;
                  });
                  _joinSmart();
                },
              ),
            ),
          ],
        ],
      ),
    );
  }
}
