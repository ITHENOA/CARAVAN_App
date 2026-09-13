import 'package:flutter/foundation.dart';

class AppLogger {
  static void d(String message, [Object? error, StackTrace? stack]) {
    if (kDebugMode) {
      debugPrint('[Caravan] $message');
      if (error != null) debugPrint('  error: $error');
      if (stack != null) debugPrint('$stack');
    }
  }

  static void debug(String message, [Object? error, StackTrace? stack]) =>
      d(message, error, stack);

  static void e(String message, [Object? error, StackTrace? stack]) {
    debugPrint('[Caravan][ERROR] $message');
    if (error != null) debugPrint('  error: $error');
    if (kDebugMode && stack != null) debugPrint('$stack');
  }
}
