import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../onboarding/presentation/profile_provider.dart';

class SettingsState {
  const SettingsState({
    this.locale,
    this.themeMode = ThemeMode.system,
    this.metric = true,
    this.voiceEnabled = true,
  });

  final Locale? locale;
  final ThemeMode themeMode;
  final bool metric;
  final bool voiceEnabled;

  SettingsState copyWith({
    Locale? locale,
    bool clearLocale = false,
    ThemeMode? themeMode,
    bool? metric,
    bool? voiceEnabled,
  }) {
    return SettingsState(
      locale: clearLocale ? null : (locale ?? this.locale),
      themeMode: themeMode ?? this.themeMode,
      metric: metric ?? this.metric,
      voiceEnabled: voiceEnabled ?? this.voiceEnabled,
    );
  }
}

class SettingsController extends Notifier<SettingsState> {
  @override
  SettingsState build() {
    final repo = ref.watch(profileRepositoryProvider);
    final code = repo.localeCode;
    final theme = repo.themeMode;
    return SettingsState(
      locale: code == null ? null : Locale(code),
      themeMode: switch (theme) {
        'light' => ThemeMode.light,
        'dark' => ThemeMode.dark,
        _ => ThemeMode.system,
      },
      metric: repo.metricUnits,
      voiceEnabled: repo.voiceEnabled,
    );
  }

  Future<void> setLocale(Locale? locale) async {
    await ref
        .read(profileRepositoryProvider)
        .setLocaleCode(locale?.languageCode);
    state = state.copyWith(locale: locale, clearLocale: locale == null);
  }

  Future<void> setThemeMode(ThemeMode mode) async {
    final raw = switch (mode) {
      ThemeMode.light => 'light',
      ThemeMode.dark => 'dark',
      _ => 'system',
    };
    await ref.read(profileRepositoryProvider).setThemeMode(raw);
    state = state.copyWith(themeMode: mode);
  }

  Future<void> setMetric(bool metric) async {
    await ref.read(profileRepositoryProvider).setMetricUnits(metric);
    state = state.copyWith(metric: metric);
  }

  Future<void> setVoice(bool enabled) async {
    await ref.read(profileRepositoryProvider).setVoiceEnabled(enabled);
    state = state.copyWith(voiceEnabled: enabled);
  }
}

final settingsControllerProvider =
    NotifierProvider<SettingsController, SettingsState>(SettingsController.new);
