import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../core/constants/app_constants.dart';
import '../core/theme/caravan_theme.dart';
import '../features/settings/presentation/settings_controller.dart';
import '../l10n/app_localizations.dart';
import 'router.dart';

class CaravanApp extends ConsumerWidget {
  const CaravanApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);
    final settings = ref.watch(settingsControllerProvider);
    return MaterialApp.router(
      title: AppConstants.productName,
      debugShowCheckedModeBanner: false,
      theme: CaravanTheme.light(),
      darkTheme: CaravanTheme.dark(),
      themeMode: settings.themeMode,
      // Product UI is English-only.
      locale: const Locale('en'),
      supportedLocales: const [Locale('en')],
      localizationsDelegates: const [
        AppLocalizations.delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
      ],
      routerConfig: router,
    );
  }
}
