import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../features/chat/presentation/chat_screen.dart';
import '../features/home/presentation/home_screen.dart';
import '../features/onboarding/presentation/onboarding_screen.dart';
import '../features/onboarding/presentation/profile_provider.dart';
import '../features/settings/presentation/debug_screen.dart';
import '../features/settings/presentation/settings_screen.dart';
import '../features/trip/presentation/create_trip_screen.dart';
import '../features/trip/presentation/join_trip_screen.dart';
import '../features/trip/presentation/trip_screen.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  final profile = ref.watch(profileProvider);
  return GoRouter(
    initialLocation: '/',
    routes: [
      GoRoute(
        path: '/',
        builder: (context, state) => profile.when(
          data: (p) =>
              p.isComplete ? const HomeScreen() : const OnboardingScreen(),
          loading: () =>
              const Scaffold(body: Center(child: CircularProgressIndicator())),
          error: (e, _) => Scaffold(body: Center(child: Text('$e'))),
        ),
      ),
      GoRoute(
        path: '/onboarding',
        builder: (_, __) => const OnboardingScreen(),
      ),
      GoRoute(path: '/home', builder: (_, __) => const HomeScreen()),
      GoRoute(path: '/create', builder: (_, __) => const CreateTripScreen()),
      GoRoute(path: '/join', builder: (_, __) => const JoinTripScreen()),
      GoRoute(path: '/trip', builder: (_, __) => const TripScreen()),
      GoRoute(path: '/chat', builder: (_, __) => const ChatScreen()),
      GoRoute(path: '/settings', builder: (_, __) => const SettingsScreen()),
      GoRoute(path: '/debug', builder: (_, __) => const DebugScreen()),
    ],
  );
});
