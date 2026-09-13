import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/caravan_theme.dart';
import '../../../l10n/app_localizations.dart';
import '../../onboarding/presentation/profile_provider.dart';
import '../../trip/data/trip_controller.dart';
import '../data/quick_prompts_controller.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key});

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final TextEditingController _textController = TextEditingController();
  final ScrollController _scrollController = ScrollController();

  @override
  void dispose() {
    _textController.dispose();
    _scrollController.dispose();
    super.dispose();
  }

  void _sendMessage([String? customText]) {
    final text = customText ?? _textController.text;
    if (text.trim().isEmpty) return;
    ref.read(tripControllerProvider.notifier).sendChatMessage(text.trim());
    if (customText == null) {
      _textController.clear();
    }
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollController.hasClients) {
        _scrollController.animateTo(
          _scrollController.position.maxScrollExtent + 80,
          duration: const Duration(milliseconds: 280),
          curve: Curves.easeOutCubic,
        );
      }
    });
  }

  Color _parseColor(String? hex) {
    if (hex == null || hex.isEmpty) return CaravanTheme.accent;
    try {
      final clean = hex.replaceAll('#', '');
      return Color(int.parse('FF$clean', radix: 16));
    } catch (_) {
      return CaravanTheme.accent;
    }
  }

  Future<void> _editPrompts() async {
    final l10n = AppLocalizations.of(context);
    final current =
        List<String>.from(ref.read(quickPromptsProvider).valueOrNull ?? defaultQuickPrompts);
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
                  ReorderableListView.builder(
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    itemCount: current.length,
                    onReorderItem: (oldIndex, newIndex) {
                      setModal(() {
                        final item = current.removeAt(oldIndex);
                        current.insert(newIndex, item);
                      });
                    },
                    itemBuilder: (context, index) {
                      return ListTile(
                        key: ValueKey('prompt_$index${current[index]}'),
                        title: Text(current[index]),
                        trailing: IconButton(
                          icon: const Icon(Icons.delete_outline),
                          onPressed: () => setModal(() => current.removeAt(index)),
                        ),
                      );
                    },
                  ),
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: controller,
                          decoration: InputDecoration(
                            hintText: l10n.addQuickMessage,
                          ),
                          onSubmitted: (v) {
                            if (v.trim().isEmpty) return;
                            setModal(() {
                              current.add(v.trim());
                              controller.clear();
                            });
                          },
                        ),
                      ),
                      IconButton(
                        onPressed: () {
                          final v = controller.text.trim();
                          if (v.isEmpty) return;
                          setModal(() {
                            current.add(v);
                            controller.clear();
                          });
                        },
                        icon: const Icon(Icons.add_circle_outline),
                      ),
                    ],
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

  @override
  Widget build(BuildContext context) {
    final l10n = AppLocalizations.of(context);
    final trip = ref.watch(tripControllerProvider);
    final myProfile = ref.watch(profileProvider).valueOrNull;
    final messages = trip.chatMessages;
    final prompts =
        ref.watch(quickPromptsProvider).valueOrNull ?? defaultQuickPrompts;
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: Text(trip.tripName ?? l10n.groupChat),
        actions: [
          TextButton(
            onPressed: _editPrompts,
            child: Text(l10n.tapToEditPrompts),
          ),
          Padding(
            padding: const EdgeInsets.only(right: 12),
            child: Center(
              child: Text(
                l10n.membersCount(trip.members.length),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          SizedBox(
            height: 52,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              itemCount: prompts.length + 1,
              separatorBuilder: (_, __) => const SizedBox(width: 8),
              itemBuilder: (context, index) {
                if (index == prompts.length) {
                  return ActionChip(
                    avatar: const Icon(Icons.tune, size: 16),
                    label: Text(l10n.editQuickMessages),
                    onPressed: _editPrompts,
                  );
                }
                final prompt = prompts[index];
                return ActionChip(
                  label: Text(prompt),
                  onPressed: () => _sendMessage(prompt),
                );
              },
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: messages.isEmpty
                ? Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Text(
                        l10n.noMessagesYet,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          color: cs.onSurface.withValues(alpha: 0.55),
                          height: 1.4,
                        ),
                      ),
                    ),
                  )
                : ListView.builder(
                    controller: _scrollController,
                    padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                    itemCount: messages.length,
                    itemBuilder: (context, index) {
                      final msg = messages[index];
                      final isMe = msg['clientId'] == trip.clientId ||
                          msg['clientId'] == myProfile?.clientId ||
                          msg['senderName'] == myProfile?.displayName;
                      final senderColor =
                          _parseColor(msg['senderColor'] as String?);
                      final timeStr = msg['timestamp'] != null
                          ? DateTime.fromMillisecondsSinceEpoch(
                              msg['timestamp'] as int,
                            ).toLocal().toString().substring(11, 16)
                          : '';

                      return Align(
                        alignment:
                            isMe ? Alignment.centerRight : Alignment.centerLeft,
                        child: TweenAnimationBuilder<double>(
                          tween: Tween(begin: 0.92, end: 1),
                          duration: const Duration(milliseconds: 180),
                          curve: Curves.easeOut,
                          builder: (context, scale, child) =>
                              Transform.scale(scale: scale, child: child),
                          child: Container(
                            margin: const EdgeInsets.symmetric(vertical: 5),
                            padding: const EdgeInsets.fromLTRB(14, 10, 14, 8),
                            constraints: BoxConstraints(
                              maxWidth: MediaQuery.of(context).size.width * 0.78,
                            ),
                            decoration: BoxDecoration(
                              gradient: isMe
                                  ? LinearGradient(
                                      colors: [
                                        cs.primary,
                                        cs.primary.withValues(alpha: 0.85),
                                      ],
                                    )
                                  : null,
                              color: isMe
                                  ? null
                                  : cs.surfaceContainerHighest.withValues(
                                      alpha: 0.9,
                                    ),
                              borderRadius: BorderRadius.circular(22),
                            ),
                            child: Column(
                              crossAxisAlignment: isMe
                                  ? CrossAxisAlignment.end
                                  : CrossAxisAlignment.start,
                              children: [
                                if (!isMe)
                                  Padding(
                                    padding: const EdgeInsets.only(bottom: 4),
                                    child: Row(
                                      mainAxisSize: MainAxisSize.min,
                                      children: [
                                        CircleAvatar(
                                          radius: 5,
                                          backgroundColor: senderColor,
                                        ),
                                        const SizedBox(width: 6),
                                        Text(
                                          msg['senderName'] as String? ??
                                              l10n.driverFallback,
                                          style: TextStyle(
                                            fontSize: 12,
                                            fontWeight: FontWeight.w800,
                                            color: senderColor,
                                          ),
                                        ),
                                      ],
                                    ),
                                  ),
                                Text(
                                  msg['text'] as String? ?? '',
                                  style: TextStyle(
                                    fontSize: 15,
                                    height: 1.35,
                                    color: isMe ? Colors.white : cs.onSurface,
                                    fontWeight: FontWeight.w600,
                                  ),
                                ),
                                const SizedBox(height: 4),
                                Text(
                                  timeStr,
                                  style: TextStyle(
                                    fontSize: 10,
                                    color: (isMe ? Colors.white : cs.onSurface)
                                        .withValues(alpha: 0.55),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                      );
                    },
                  ),
          ),
          SafeArea(
            top: false,
            child: Container(
              padding: const EdgeInsets.fromLTRB(12, 8, 12, 10),
              decoration: BoxDecoration(
                color: cs.surface,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.05),
                    blurRadius: 8,
                    offset: const Offset(0, -2),
                  ),
                ],
              ),
              child: Row(
                children: [
                  Expanded(
                    child: TextField(
                      controller: _textController,
                      decoration: InputDecoration(
                        hintText: l10n.typeMessageHint,
                      ),
                      onSubmitted: (_) => _sendMessage(),
                    ),
                  ),
                  const SizedBox(width: 8),
                  IconButton.filled(
                    onPressed: () => _sendMessage(),
                    icon: const Icon(Icons.send_rounded),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
