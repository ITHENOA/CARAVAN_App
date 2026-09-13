import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_constants.dart';
import '../../../l10n/app_localizations.dart';
import 'profile_provider.dart';

class OnboardingScreen extends ConsumerStatefulWidget {
  const OnboardingScreen({super.key});

  @override
  ConsumerState<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends ConsumerState<OnboardingScreen> {
  final _name = TextEditingController();
  final _car = TextEditingController();
  String _color = '#2E7D32';
  static const _colors = [
    '#2E7D32',
    '#1565C0',
    '#C62828',
    '#6A1B9A',
    '#EF6C00',
    '#00838F',
  ];

  @override
  void dispose() {
    _name.dispose();
    _car.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final profile = ref.watch(profileProvider).valueOrNull;
    if (profile != null &&
        _name.text.isEmpty &&
        profile.displayName.isNotEmpty) {
      _name.text = profile.displayName;
      _car.text = profile.carName ?? '';
      _color = profile.avatarColor;
    }

    return Scaffold(
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(24, 32, 24, 24),
          children: [
            Text(
              AppConstants.productName,
              style: Theme.of(
                context,
              ).textTheme.displaySmall?.copyWith(fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: 8),
            Text(
              l10n.welcomeTitle,
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(
              l10n.welcomeSubtitle,
              style: Theme.of(context).textTheme.bodyLarge?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
            const SizedBox(height: 32),
            TextField(
              controller: _name,
              textInputAction: TextInputAction.next,
              decoration: InputDecoration(
                labelText: l10n.displayName,
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _car,
              decoration: InputDecoration(
                labelText: l10n.carName,
                border: const OutlineInputBorder(),
              ),
            ),
            const SizedBox(height: 16),
            Text(l10n.carColor),
            const SizedBox(height: 8),
            Wrap(
              spacing: 10,
              children: _colors.map((c) {
                final selected = c == _color;
                return GestureDetector(
                  onTap: () => setState(() => _color = c),
                  child: Container(
                    width: 44,
                    height: 44,
                    decoration: BoxDecoration(
                      color: Color(
                        int.parse(c.substring(1), radix: 16) + 0xFF000000,
                      ),
                      shape: BoxShape.circle,
                      border: Border.all(
                        width: selected ? 3 : 1,
                        color: selected
                            ? Theme.of(context).colorScheme.onSurface
                            : Colors.black26,
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
            const SizedBox(height: 32),
            FilledButton(
              onPressed: () async {
                final name = _name.text.trim();
                if (name.isEmpty) return;
                final existing = await ref.read(profileProvider.future);
                await ref
                    .read(profileProvider.notifier)
                    .save(
                      UserProfile(
                        clientId: existing.clientId,
                        displayName: name,
                        carName: _car.text.trim().isEmpty
                            ? null
                            : _car.text.trim(),
                        avatarColor: _color,
                      ),
                    );
                if (context.mounted) context.go('/home');
              },
              child: Text(l10n.continueAction),
            ),
          ],
        ),
      ),
    );
  }
}
