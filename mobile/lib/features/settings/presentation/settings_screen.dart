import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/constants/app_constants.dart';
import '../../../l10n/app_localizations.dart';
import '../../chat/data/quick_prompts_controller.dart';
import '../../onboarding/presentation/profile_provider.dart';
import 'settings_controller.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final l10n = AppLocalizations.of(context);
    final settings = ref.watch(settingsControllerProvider);
    final profile = ref.watch(profileProvider).valueOrNull;
    final prompts =
        ref.watch(quickPromptsProvider).valueOrNull ?? defaultQuickPrompts;

    return Scaffold(
      appBar: AppBar(title: Text(l10n.settings)),
      body: ListView(
        children: [
          ListTile(
            title: Text(l10n.displayName),
            subtitle: Text(
              profile?.displayName.isNotEmpty == true
                  ? profile!.displayName
                  : l10n.displayName,
            ),
            trailing: const Icon(Icons.edit_outlined),
            onTap: () => _editDisplayName(context, ref),
          ),
          ListTile(
            title: Text(l10n.theme),
            subtitle: Text(switch (settings.themeMode) {
              ThemeMode.light => l10n.themeLight,
              ThemeMode.dark => l10n.themeDark,
              _ => l10n.themeSystem,
            }),
            onTap: () async {
              final modes = [ThemeMode.system, ThemeMode.light, ThemeMode.dark];
              final i = modes.indexOf(settings.themeMode);
              await ref
                  .read(settingsControllerProvider.notifier)
                  .setThemeMode(modes[(i + 1) % modes.length]);
            },
          ),
          SwitchListTile(
            title: Text(l10n.units),
            subtitle: Text(settings.metric ? l10n.metric : l10n.imperial),
            value: settings.metric,
            onChanged: (v) =>
                ref.read(settingsControllerProvider.notifier).setMetric(v),
          ),
          SwitchListTile(
            title: Text(l10n.voiceEnabled),
            value: settings.voiceEnabled,
            onChanged: (v) =>
                ref.read(settingsControllerProvider.notifier).setVoice(v),
          ),
          ListTile(
            title: Text(l10n.editQuickMessages),
            subtitle: Text(prompts.take(3).join(' · ')),
            trailing: const Icon(Icons.chevron_right),
            onTap: () => _editQuickMessages(context, ref),
          ),
          ListTile(
            title: Text(l10n.about),
            subtitle: const Text('${AppConstants.productName} 1.0.0'),
          ),
          if (kDebugMode)
            ListTile(
              title: Text(l10n.debugDiagnostics),
              trailing: const Icon(Icons.chevron_right),
              onTap: () => context.push('/debug'),
            ),
        ],
      ),
    );
  }

  Future<void> _editDisplayName(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final profile = ref.read(profileProvider).valueOrNull;
    if (profile == null) return;
    final controller = TextEditingController(text: profile.displayName);
    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(l10n.displayName),
        content: TextField(
          controller: controller,
          autofocus: true,
          textCapitalization: TextCapitalization.words,
          decoration: InputDecoration(hintText: l10n.displayName),
          onSubmitted: (_) => Navigator.pop(context, true),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(l10n.cancel),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(l10n.save),
          ),
        ],
      ),
    );
    if (ok != true) return;
    final name = controller.text.trim();
    if (name.isEmpty) return;
    await ref.read(profileProvider.notifier).save(
          UserProfile(
            clientId: profile.clientId,
            displayName: name,
            carName: profile.carName,
            avatarColor: profile.avatarColor,
          ),
        );
  }

  Future<void> _editQuickMessages(BuildContext context, WidgetRef ref) async {
    final l10n = AppLocalizations.of(context);
    final current = List<String>.from(
      ref.read(quickPromptsProvider).valueOrNull ?? defaultQuickPrompts,
    );
    final controller = TextEditingController();
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) {
        return StatefulBuilder(
          builder: (context, setModal) {
            return Padding(
              padding: EdgeInsets.only(
                left: 20,
                right: 20,
                top: 16,
                bottom: MediaQuery.of(context).viewInsets.bottom + 24,
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    l10n.editQuickMessages,
                    style: Theme.of(context).textTheme.titleLarge?.copyWith(
                          fontWeight: FontWeight.w800,
                        ),
                  ),
                  const SizedBox(height: 12),
                  ...[
                    for (var i = 0; i < current.length; i++)
                      ListTile(
                        title: Text(current[i]),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () => setModal(() => current.removeAt(i)),
                        ),
                      ),
                  ],
                  TextField(
                    controller: controller,
                    decoration: InputDecoration(hintText: l10n.addQuickMessage),
                    onSubmitted: (v) {
                      if (v.trim().isEmpty) return;
                      setModal(() {
                        current.add(v.trim());
                        controller.clear();
                      });
                    },
                  ),
                  const SizedBox(height: 12),
                  Row(
                    children: [
                      TextButton(
                        onPressed: () async {
                          await ref
                              .read(quickPromptsProvider.notifier)
                              .resetDefaults();
                          if (context.mounted) Navigator.pop(context);
                        },
                        child: Text(l10n.resetQuickMessages),
                      ),
                      const Spacer(),
                      FilledButton(
                        onPressed: () async {
                          await ref
                              .read(quickPromptsProvider.notifier)
                              .setAll(current);
                          if (context.mounted) Navigator.pop(context);
                        },
                        child: Text(l10n.save),
                      ),
                    ],
                  ),
                ],
              ),
            );
          },
        );
      },
    );
  }
}
