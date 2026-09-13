// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get appTitle => 'Caravan';

  @override
  String get createTrip => 'Create Trip';

  @override
  String get joinTrip => 'Join Trip';

  @override
  String get holdToTalk => 'Hold to talk';

  @override
  String get destination => 'Destination';

  @override
  String get members => 'Members';

  @override
  String get leader => 'Leader';

  @override
  String get offline => 'Offline';

  @override
  String get reconnecting => 'Reconnecting';

  @override
  String get connected => 'Connected';

  @override
  String get displayName => 'Display name';

  @override
  String get carName => 'Car name (optional)';

  @override
  String get carColor => 'Car color';

  @override
  String get continueAction => 'Continue';

  @override
  String get welcomeTitle => 'Travel together';

  @override
  String get welcomeSubtitle =>
      'Share live positions, one destination, and walkie-talkie voice with your convoy.';

  @override
  String get tripCode => 'Trip code';

  @override
  String get enterTripCode => 'Enter trip code';

  @override
  String get scanQr => 'Scan QR';

  @override
  String get copyCode => 'Copy code';

  @override
  String get shareTrip => 'Share trip';

  @override
  String get leaveTrip => 'Leave Trip';

  @override
  String get settings => 'Settings';

  @override
  String get language => 'Language';

  @override
  String get theme => 'Theme';

  @override
  String get themeSystem => 'System';

  @override
  String get themeLight => 'Light';

  @override
  String get themeDark => 'Dark';

  @override
  String get units => 'Units';

  @override
  String get metric => 'Metric';

  @override
  String get imperial => 'Imperial';

  @override
  String get voiceEnabled => 'Voice enabled';

  @override
  String get locationSharing => 'Location sharing';

  @override
  String get about => 'About';

  @override
  String get mapStyle => 'Map style';

  @override
  String get recenter => 'Recenter';

  @override
  String ahead(String distance) {
    return '$distance ahead';
  }

  @override
  String behind(String distance) {
    return '$distance behind';
  }

  @override
  String away(String distance) {
    return '$distance away';
  }

  @override
  String isTalking(String name) {
    return '$name is talking';
  }

  @override
  String get pttBusy => 'Someone else is talking';

  @override
  String destinationChangedBy(String name) {
    return 'Destination changed by $name';
  }

  @override
  String get unableToJoin => 'Unable to join trip.';

  @override
  String get connectionLost => 'Connection lost. Reconnecting…';

  @override
  String get micPermissionRequired => 'Microphone permission required.';

  @override
  String get locationPermissionRequired => 'Location permission required.';

  @override
  String get voiceUnavailable => 'Voice connection unavailable.';

  @override
  String get routingFailed => 'Destination could not be routed.';

  @override
  String get setDestinationHint => 'Long-press map to set destination';

  @override
  String get openInMaps => 'Navigate';

  @override
  String staleSeconds(String name, int seconds) {
    return '$name · ${seconds}s ago';
  }

  @override
  String get nearbyMembers => 'Nearby members';

  @override
  String get debugDiagnostics => 'Diagnostics';

  @override
  String get mockMode => 'Mock mode';

  @override
  String get english => 'English';

  @override
  String get persian => 'Persian';

  @override
  String get save => 'Save';

  @override
  String get cancel => 'Cancel';

  @override
  String get you => 'You';

  @override
  String get tripCreated => 'Trip created';

  @override
  String get waitingForGps => 'Waiting for GPS…';

  @override
  String get locationSharingActive => 'Location sharing is active';

  @override
  String get inviteCode => 'Invite code';

  @override
  String get tripDetails => 'Trip details';

  @override
  String carsInConvoy(int count) {
    return '$count cars in convoy';
  }

  @override
  String get quickMessages => 'Quick messages';

  @override
  String get openChat => 'Open chat';

  @override
  String get groupChat => 'Group chat';

  @override
  String membersCount(int count) {
    return '$count members';
  }

  @override
  String get noMessagesYet =>
      'No messages yet.\nUse quick messages or type below.';

  @override
  String get typeMessageHint => 'Write a message…';

  @override
  String get driverFallback => 'Driver';

  @override
  String get searchPlace => 'Search place';

  @override
  String get routeEta => 'Route ETA';

  @override
  String get convoySpeed => 'Convoy speed';

  @override
  String get getRoute => 'Get route';

  @override
  String get navigate => 'Navigate';

  @override
  String get setAsDestination => 'Set destination';

  @override
  String get editQuickMessages => 'Edit quick messages';

  @override
  String get addQuickMessage => 'Add quick message';

  @override
  String get resetQuickMessages => 'Reset to defaults';

  @override
  String get pttRequesting => 'Requesting…';

  @override
  String get pttLive => 'Live';

  @override
  String get pttFailed => 'Voice failed';

  @override
  String get pttIceFailed => 'Network blocked voice';

  @override
  String get pttTimeout => 'No floor grant';

  @override
  String get searchingPlaces => 'Searching…';

  @override
  String get noPlaceResults => 'No places found';

  @override
  String get leaderOnlyDestination => 'Only the leader can set the destination';

  @override
  String distanceEta(String distance, String eta) {
    return '$distance · $eta';
  }

  @override
  String get convoyStrip => 'Convoy';

  @override
  String get tapToEditPrompts => 'Customize';

  @override
  String get startNavigation => 'Start navigation';

  @override
  String get tripNameLabel => 'Trip name';

  @override
  String get tripNameHint => 'e.g. Weekend drive';

  @override
  String get createTripAction => 'Create trip';

  @override
  String get deleteTrip => 'Delete trip';

  @override
  String get deleteTripConfirm =>
      'Delete this trip for everyone? This cannot be undone.';

  @override
  String get tripDeleted => 'Trip deleted';

  @override
  String get enableGpsTitle => 'Location is off';

  @override
  String get enableGpsMessage =>
      'Turn on GPS so we can show your position on the map?';

  @override
  String get enableGps => 'Turn on';
}
