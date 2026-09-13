import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _prefsKey = 'quick_prompts_v1';

const defaultQuickPrompts = <String>[
  'Wait',
  'Stopping',
  'Fuel',
  'Slow down',
  "I'm here",
  'All clear',
];

class QuickPromptsController extends AsyncNotifier<List<String>> {
  @override
  Future<List<String>> build() async {
    final prefs = await SharedPreferences.getInstance();
    final stored = prefs.getStringList(_prefsKey);
    if (stored == null || stored.isEmpty) return List.of(defaultQuickPrompts);
    return stored;
  }

  Future<void> _persist(List<String> next) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_prefsKey, next);
    state = AsyncData(next);
  }

  Future<void> setAll(List<String> prompts) async {
    final cleaned = prompts.map((e) => e.trim()).where((e) => e.isNotEmpty).toList();
    await _persist(cleaned.isEmpty ? List.of(defaultQuickPrompts) : cleaned);
  }

  Future<void> add(String prompt) async {
    final text = prompt.trim();
    if (text.isEmpty) return;
    final current = List<String>.from(state.valueOrNull ?? defaultQuickPrompts);
    if (current.contains(text)) return;
    current.add(text);
    await _persist(current);
  }

  Future<void> removeAt(int index) async {
    final current = List<String>.from(state.valueOrNull ?? defaultQuickPrompts);
    if (index < 0 || index >= current.length) return;
    current.removeAt(index);
    await _persist(current);
  }

  Future<void> updateAt(int index, String prompt) async {
    final text = prompt.trim();
    if (text.isEmpty) return;
    final current = List<String>.from(state.valueOrNull ?? defaultQuickPrompts);
    if (index < 0 || index >= current.length) return;
    current[index] = text;
    await _persist(current);
  }

  Future<void> reorder(int oldIndex, int newIndex) async {
    final current = List<String>.from(state.valueOrNull ?? defaultQuickPrompts);
    if (newIndex > oldIndex) newIndex -= 1;
    final item = current.removeAt(oldIndex);
    current.insert(newIndex, item);
    await _persist(current);
  }

  Future<void> resetDefaults() async {
    await _persist(List.of(defaultQuickPrompts));
  }
}

final quickPromptsProvider =
    AsyncNotifierProvider<QuickPromptsController, List<String>>(
  QuickPromptsController.new,
);
