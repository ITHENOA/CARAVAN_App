import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_constants.dart';
import '../../../l10n/app_localizations.dart';
import '../../onboarding/presentation/profile_provider.dart';
import '../../trip/data/trip_controller.dart';

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen> {
  Future<void> _confirmDeleteTrip(AppLocalizations l10n) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.deleteTrip),
        content: Text(l10n.deleteTripConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l10n.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l10n.deleteTrip),
          ),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final done =
        await ref.read(tripControllerProvider.notifier).deleteSavedTrip();
    if (!mounted) return;
    setState(() {});
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(done ? l10n.tripDeleted : l10n.deleteTripConfirm),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final profile = ref.watch(profileProvider).valueOrNull;
    final lastTrip = ref.watch(profileRepositoryProvider).getLastTrip();

    return Scaffold(
      appBar: AppBar(
        title: const Text(AppConstants.productName),
        actions: [
          IconButton(
            tooltip: l10n.settings,
            onPressed: () => context.push('/settings'),
            icon: const Icon(Icons.settings_outlined),
          ),
        ],
      ),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(
              profile?.displayName ?? '',
              style: Theme.of(
                context,
              ).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w700),
            ),
            if (profile?.carName != null) ...[
              const SizedBox(height: 4),
              Text(
                profile!.carName!,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
              ),
            ],
            const Spacer(),
            if (lastTrip != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 12),
                child: Card(
                  elevation: 0,
                  color: Theme.of(context)
                      .colorScheme
                      .primaryContainer
                      .withValues(alpha: 0.5),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                    side: BorderSide(
                      color: Theme.of(context)
                          .colorScheme
                          .primary
                          .withValues(alpha: 0.3),
                    ),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Row(
                          children: [
                            Icon(
                              Icons.history,
                              color: Theme.of(context).colorScheme.primary,
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                lastTrip['tripName']?.isNotEmpty == true
                                    ? lastTrip['tripName']!
                                    : 'Previous trip',
                                style: Theme.of(context)
                                    .textTheme
                                    .titleMedium
                                    ?.copyWith(fontWeight: FontWeight.bold),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 6),
                        Text(
                          '${l10n.inviteCode}: ${lastTrip['inviteCode']}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                        const SizedBox(height: 12),
                        FilledButton.tonalIcon(
                          onPressed: () async {
                            await ref
                                .read(tripControllerProvider.notifier)
                                .joinTrip(
                                  tripId: lastTrip['tripId']!,
                                  inviteCode: lastTrip['inviteCode']!,
                                  leaderToken: lastTrip['leaderToken'],
                                );
                            if (context.mounted) {
                              context.go('/trip');
                            }
                          },
                          icon: const Icon(Icons.login),
                          label: const Text('Rejoin this trip'),
                        ),
                        if ((lastTrip['leaderToken'] ?? '').isNotEmpty) ...[
                          const SizedBox(height: 8),
                          OutlinedButton.icon(
                            onPressed: () => _confirmDeleteTrip(l10n),
                            icon: const Icon(
                              Icons.delete_forever,
                              color: Colors.red,
                            ),
                            label: Text(
                              l10n.deleteTrip,
                              style: const TextStyle(color: Colors.red),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
              ),
            FilledButton.icon(
              onPressed: () => context.push('/create'),
              icon: const Icon(Icons.add_road),
              label: Text(l10n.createTrip),
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              style: OutlinedButton.styleFrom(minimumSize: const Size(48, 52)),
              onPressed: () => context.push('/join'),
              icon: const Icon(Icons.qr_code_2),
              label: Text(l10n.joinTrip),
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }
}
