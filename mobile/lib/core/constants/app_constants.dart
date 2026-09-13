/// Single place to rename the product.
class AppConstants {
  const AppConstants._();

  static const String productName = 'Caravan';
  static const String packageId = 'com.caravan.app';
  static const int protocolVersion = 1;
  static const int maxDisplayName = 40;
  static const int maxCarName = 40;
  static const int maxDestinationLabel = 80;
  static const int maxPayloadBytes = 32 * 1024;
  static const String defaultTripName = 'Caravan Trip';
  static const String defaultAvatarColor = '#2E7D32';
  static const Duration locationMovingInterval = Duration(seconds: 2);
  static const Duration locationStoppedInterval = Duration(seconds: 12);
  static const double meaningfulMoveMeters = 8;
}
