import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:share_plus/share_plus.dart';

import '../../../l10n/app_localizations.dart';
import '../data/trip_controller.dart';

class CreateTripScreen extends ConsumerStatefulWidget {
  const CreateTripScreen({super.key});

  @override
  ConsumerState<CreateTripScreen> createState() => _CreateTripScreenState();
}

class _CreateTripScreenState extends ConsumerState<CreateTripScreen> {
  final _nameController = TextEditingController();
  bool _busy = false;
  bool _created = false;
  String? _error;

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<void> _create() async {
    final name = _nameController.text.trim();
    if (name.isEmpty) {
      setState(() => _error = 'Enter a trip name');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await ref.read(tripControllerProvider.notifier).createTrip(name: name);
      if (mounted) {
        setState(() {
          _busy = false;
          _created = true;
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _busy = false;
          _error = e.toString();
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final trip = ref.watch(tripControllerProvider);

    return Scaffold(
      appBar: AppBar(title: Text(l10n.createTrip)),
      body: _busy
          ? const Center(child: CircularProgressIndicator())
          : !_created
              ? ListView(
                  padding: const EdgeInsets.all(24),
                  children: [
                    Text(
                      l10n.tripNameLabel,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(
                            fontWeight: FontWeight.w800,
                          ),
                    ),
                    const SizedBox(height: 8),
                    TextField(
                      controller: _nameController,
                      textInputAction: TextInputAction.done,
                      decoration: InputDecoration(
                        hintText: l10n.tripNameHint,
                      ),
                      onSubmitted: (_) => _create(),
                      autofocus: true,
                    ),
                    if (_error != null) ...[
                      const SizedBox(height: 12),
                      Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                    ],
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: _create,
                      child: Text(l10n.createTripAction),
                    ),
                  ],
                )
              : ListView(
                  padding: const EdgeInsets.all(24),
                  children: [
                    Text(
                      l10n.tripCreated,
                      style: Theme.of(context).textTheme.headlineSmall,
                    ),
                    const SizedBox(height: 8),
                    Text(
                      trip.tripName ?? '',
                      style: Theme.of(context).textTheme.titleLarge?.copyWith(
                            fontWeight: FontWeight.w700,
                          ),
                    ),
                    const SizedBox(height: 16),
                    Text(l10n.tripCode),
                    SelectableText(
                      trip.inviteCode ?? '',
                      style: Theme.of(context).textTheme.displaySmall?.copyWith(
                            fontWeight: FontWeight.w800,
                            letterSpacing: 2,
                          ),
                    ),
                    const SizedBox(height: 24),
                    Center(
                      child: QrImageView(
                        data:
                            'caravan://join/${trip.tripId}?code=${trip.inviteCode}',
                        size: 220,
                      ),
                    ),
                    const SizedBox(height: 24),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton(
                            onPressed: () {
                              Clipboard.setData(
                                ClipboardData(text: trip.inviteCode ?? ''),
                              );
                            },
                            child: Text(l10n.copyCode),
                          ),
                        ),
                        const SizedBox(width: 12),
                        Expanded(
                          child: OutlinedButton(
                            onPressed: () {
                              Share.share(
                                'Join my Caravan trip "${trip.tripName}": ${trip.inviteCode}',
                              );
                            },
                            child: Text(l10n.shareTrip),
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 24),
                    FilledButton(
                      onPressed: trip.joined ? () => context.go('/trip') : null,
                      child: Text(l10n.continueAction),
                    ),
                  ],
                ),
    );
  }
}
