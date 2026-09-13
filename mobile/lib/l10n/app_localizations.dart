import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations)!;
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[Locale('en')];

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Caravan'**
  String get appTitle;

  /// No description provided for @createTrip.
  ///
  /// In en, this message translates to:
  /// **'Create Trip'**
  String get createTrip;

  /// No description provided for @joinTrip.
  ///
  /// In en, this message translates to:
  /// **'Join Trip'**
  String get joinTrip;

  /// No description provided for @holdToTalk.
  ///
  /// In en, this message translates to:
  /// **'Hold to talk'**
  String get holdToTalk;

  /// No description provided for @destination.
  ///
  /// In en, this message translates to:
  /// **'Destination'**
  String get destination;

  /// No description provided for @members.
  ///
  /// In en, this message translates to:
  /// **'Members'**
  String get members;

  /// No description provided for @leader.
  ///
  /// In en, this message translates to:
  /// **'Leader'**
  String get leader;

  /// No description provided for @offline.
  ///
  /// In en, this message translates to:
  /// **'Offline'**
  String get offline;

  /// No description provided for @reconnecting.
  ///
  /// In en, this message translates to:
  /// **'Reconnecting'**
  String get reconnecting;

  /// No description provided for @connected.
  ///
  /// In en, this message translates to:
  /// **'Connected'**
  String get connected;

  /// No description provided for @displayName.
  ///
  /// In en, this message translates to:
  /// **'Display name'**
  String get displayName;

  /// No description provided for @carName.
  ///
  /// In en, this message translates to:
  /// **'Car name (optional)'**
  String get carName;

  /// No description provided for @carColor.
  ///
  /// In en, this message translates to:
  /// **'Car color'**
  String get carColor;

  /// No description provided for @continueAction.
  ///
  /// In en, this message translates to:
  /// **'Continue'**
  String get continueAction;

  /// No description provided for @welcomeTitle.
  ///
  /// In en, this message translates to:
  /// **'Travel together'**
  String get welcomeTitle;

  /// No description provided for @welcomeSubtitle.
  ///
  /// In en, this message translates to:
  /// **'Share live positions, one destination, and walkie-talkie voice with your convoy.'**
  String get welcomeSubtitle;

  /// No description provided for @tripCode.
  ///
  /// In en, this message translates to:
  /// **'Trip code'**
  String get tripCode;

  /// No description provided for @enterTripCode.
  ///
  /// In en, this message translates to:
  /// **'Enter trip code'**
  String get enterTripCode;

  /// No description provided for @scanQr.
  ///
  /// In en, this message translates to:
  /// **'Scan QR'**
  String get scanQr;

  /// No description provided for @copyCode.
  ///
  /// In en, this message translates to:
  /// **'Copy code'**
  String get copyCode;

  /// No description provided for @shareTrip.
  ///
  /// In en, this message translates to:
  /// **'Share trip'**
  String get shareTrip;

  /// No description provided for @leaveTrip.
  ///
  /// In en, this message translates to:
  /// **'Leave Trip'**
  String get leaveTrip;

  /// No description provided for @settings.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settings;

  /// No description provided for @language.
  ///
  /// In en, this message translates to:
  /// **'Language'**
  String get language;

  /// No description provided for @theme.
  ///
  /// In en, this message translates to:
  /// **'Theme'**
  String get theme;

  /// No description provided for @themeSystem.
  ///
  /// In en, this message translates to:
  /// **'System'**
  String get themeSystem;

  /// No description provided for @themeLight.
  ///
  /// In en, this message translates to:
  /// **'Light'**
  String get themeLight;

  /// No description provided for @themeDark.
  ///
  /// In en, this message translates to:
  /// **'Dark'**
  String get themeDark;

  /// No description provided for @units.
  ///
  /// In en, this message translates to:
  /// **'Units'**
  String get units;

  /// No description provided for @metric.
  ///
  /// In en, this message translates to:
  /// **'Metric'**
  String get metric;

  /// No description provided for @imperial.
  ///
  /// In en, this message translates to:
  /// **'Imperial'**
  String get imperial;

  /// No description provided for @voiceEnabled.
  ///
  /// In en, this message translates to:
  /// **'Voice enabled'**
  String get voiceEnabled;

  /// No description provided for @locationSharing.
  ///
  /// In en, this message translates to:
  /// **'Location sharing'**
  String get locationSharing;

  /// No description provided for @about.
  ///
  /// In en, this message translates to:
  /// **'About'**
  String get about;

  /// No description provided for @mapStyle.
  ///
  /// In en, this message translates to:
  /// **'Map style'**
  String get mapStyle;

  /// No description provided for @recenter.
  ///
  /// In en, this message translates to:
  /// **'Recenter'**
  String get recenter;

  /// No description provided for @ahead.
  ///
  /// In en, this message translates to:
  /// **'{distance} ahead'**
  String ahead(String distance);

  /// No description provided for @behind.
  ///
  /// In en, this message translates to:
  /// **'{distance} behind'**
  String behind(String distance);

  /// No description provided for @away.
  ///
  /// In en, this message translates to:
  /// **'{distance} away'**
  String away(String distance);

  /// No description provided for @isTalking.
  ///
  /// In en, this message translates to:
  /// **'{name} is talking'**
  String isTalking(String name);

  /// No description provided for @pttBusy.
  ///
  /// In en, this message translates to:
  /// **'Someone else is talking'**
  String get pttBusy;

  /// No description provided for @destinationChangedBy.
  ///
  /// In en, this message translates to:
  /// **'Destination changed by {name}'**
  String destinationChangedBy(String name);

  /// No description provided for @unableToJoin.
  ///
  /// In en, this message translates to:
  /// **'Unable to join trip.'**
  String get unableToJoin;

  /// No description provided for @connectionLost.
  ///
  /// In en, this message translates to:
  /// **'Connection lost. Reconnecting…'**
  String get connectionLost;

  /// No description provided for @micPermissionRequired.
  ///
  /// In en, this message translates to:
  /// **'Microphone permission required.'**
  String get micPermissionRequired;

  /// No description provided for @locationPermissionRequired.
  ///
  /// In en, this message translates to:
  /// **'Location permission required.'**
  String get locationPermissionRequired;

  /// No description provided for @voiceUnavailable.
  ///
  /// In en, this message translates to:
  /// **'Voice connection unavailable.'**
  String get voiceUnavailable;

  /// No description provided for @routingFailed.
  ///
  /// In en, this message translates to:
  /// **'Destination could not be routed.'**
  String get routingFailed;

  /// No description provided for @setDestinationHint.
  ///
  /// In en, this message translates to:
  /// **'Long-press map to set destination'**
  String get setDestinationHint;

  /// No description provided for @openInMaps.
  ///
  /// In en, this message translates to:
  /// **'Navigate'**
  String get openInMaps;

  /// No description provided for @staleSeconds.
  ///
  /// In en, this message translates to:
  /// **'{name} · {seconds}s ago'**
  String staleSeconds(String name, int seconds);

  /// No description provided for @nearbyMembers.
  ///
  /// In en, this message translates to:
  /// **'Nearby members'**
  String get nearbyMembers;

  /// No description provided for @debugDiagnostics.
  ///
  /// In en, this message translates to:
  /// **'Diagnostics'**
  String get debugDiagnostics;

  /// No description provided for @mockMode.
  ///
  /// In en, this message translates to:
  /// **'Mock mode'**
  String get mockMode;

  /// No description provided for @english.
  ///
  /// In en, this message translates to:
  /// **'English'**
  String get english;

  /// No description provided for @persian.
  ///
  /// In en, this message translates to:
  /// **'Persian'**
  String get persian;

  /// No description provided for @save.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get save;

  /// No description provided for @cancel.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancel;

  /// No description provided for @you.
  ///
  /// In en, this message translates to:
  /// **'You'**
  String get you;

  /// No description provided for @tripCreated.
  ///
  /// In en, this message translates to:
  /// **'Trip created'**
  String get tripCreated;

  /// No description provided for @waitingForGps.
  ///
  /// In en, this message translates to:
  /// **'Waiting for GPS…'**
  String get waitingForGps;

  /// No description provided for @locationSharingActive.
  ///
  /// In en, this message translates to:
  /// **'Location sharing is active'**
  String get locationSharingActive;

  /// No description provided for @inviteCode.
  ///
  /// In en, this message translates to:
  /// **'Invite code'**
  String get inviteCode;

  /// No description provided for @tripDetails.
  ///
  /// In en, this message translates to:
  /// **'Trip details'**
  String get tripDetails;

  /// No description provided for @carsInConvoy.
  ///
  /// In en, this message translates to:
  /// **'{count} cars in convoy'**
  String carsInConvoy(int count);

  /// No description provided for @quickMessages.
  ///
  /// In en, this message translates to:
  /// **'Quick messages'**
  String get quickMessages;

  /// No description provided for @openChat.
  ///
  /// In en, this message translates to:
  /// **'Open chat'**
  String get openChat;

  /// No description provided for @groupChat.
  ///
  /// In en, this message translates to:
  /// **'Group chat'**
  String get groupChat;

  /// No description provided for @membersCount.
  ///
  /// In en, this message translates to:
  /// **'{count} members'**
  String membersCount(int count);

  /// No description provided for @noMessagesYet.
  ///
  /// In en, this message translates to:
  /// **'No messages yet.\nUse quick messages or type below.'**
  String get noMessagesYet;

  /// No description provided for @typeMessageHint.
  ///
  /// In en, this message translates to:
  /// **'Write a message…'**
  String get typeMessageHint;

  /// No description provided for @driverFallback.
  ///
  /// In en, this message translates to:
  /// **'Driver'**
  String get driverFallback;

  /// No description provided for @searchPlace.
  ///
  /// In en, this message translates to:
  /// **'Search place'**
  String get searchPlace;

  /// No description provided for @routeEta.
  ///
  /// In en, this message translates to:
  /// **'Route ETA'**
  String get routeEta;

  /// No description provided for @convoySpeed.
  ///
  /// In en, this message translates to:
  /// **'Convoy speed'**
  String get convoySpeed;

  /// No description provided for @getRoute.
  ///
  /// In en, this message translates to:
  /// **'Get route'**
  String get getRoute;

  /// No description provided for @navigate.
  ///
  /// In en, this message translates to:
  /// **'Navigate'**
  String get navigate;

  /// No description provided for @setAsDestination.
  ///
  /// In en, this message translates to:
  /// **'Set destination'**
  String get setAsDestination;

  /// No description provided for @editQuickMessages.
  ///
  /// In en, this message translates to:
  /// **'Edit quick messages'**
  String get editQuickMessages;

  /// No description provided for @addQuickMessage.
  ///
  /// In en, this message translates to:
  /// **'Add quick message'**
  String get addQuickMessage;

  /// No description provided for @resetQuickMessages.
  ///
  /// In en, this message translates to:
  /// **'Reset to defaults'**
  String get resetQuickMessages;

  /// No description provided for @pttRequesting.
  ///
  /// In en, this message translates to:
  /// **'Requesting…'**
  String get pttRequesting;

  /// No description provided for @pttLive.
  ///
  /// In en, this message translates to:
  /// **'Live'**
  String get pttLive;

  /// No description provided for @pttFailed.
  ///
  /// In en, this message translates to:
  /// **'Voice failed'**
  String get pttFailed;

  /// No description provided for @pttIceFailed.
  ///
  /// In en, this message translates to:
  /// **'Network blocked voice'**
  String get pttIceFailed;

  /// No description provided for @pttTimeout.
  ///
  /// In en, this message translates to:
  /// **'No floor grant'**
  String get pttTimeout;

  /// No description provided for @searchingPlaces.
  ///
  /// In en, this message translates to:
  /// **'Searching…'**
  String get searchingPlaces;

  /// No description provided for @noPlaceResults.
  ///
  /// In en, this message translates to:
  /// **'No places found'**
  String get noPlaceResults;

  /// No description provided for @leaderOnlyDestination.
  ///
  /// In en, this message translates to:
  /// **'Only the leader can set the destination'**
  String get leaderOnlyDestination;

  /// No description provided for @distanceEta.
  ///
  /// In en, this message translates to:
  /// **'{distance} · {eta}'**
  String distanceEta(String distance, String eta);

  /// No description provided for @convoyStrip.
  ///
  /// In en, this message translates to:
  /// **'Convoy'**
  String get convoyStrip;

  /// No description provided for @tapToEditPrompts.
  ///
  /// In en, this message translates to:
  /// **'Customize'**
  String get tapToEditPrompts;

  /// No description provided for @startNavigation.
  ///
  /// In en, this message translates to:
  /// **'Start navigation'**
  String get startNavigation;

  /// No description provided for @tripNameLabel.
  ///
  /// In en, this message translates to:
  /// **'Trip name'**
  String get tripNameLabel;

  /// No description provided for @tripNameHint.
  ///
  /// In en, this message translates to:
  /// **'e.g. Weekend drive'**
  String get tripNameHint;

  /// No description provided for @createTripAction.
  ///
  /// In en, this message translates to:
  /// **'Create trip'**
  String get createTripAction;

  /// No description provided for @deleteTrip.
  ///
  /// In en, this message translates to:
  /// **'Delete trip'**
  String get deleteTrip;

  /// No description provided for @deleteTripConfirm.
  ///
  /// In en, this message translates to:
  /// **'Delete this trip for everyone? This cannot be undone.'**
  String get deleteTripConfirm;

  /// No description provided for @tripDeleted.
  ///
  /// In en, this message translates to:
  /// **'Trip deleted'**
  String get tripDeleted;

  /// No description provided for @enableGpsTitle.
  ///
  /// In en, this message translates to:
  /// **'Location is off'**
  String get enableGpsTitle;

  /// No description provided for @enableGpsMessage.
  ///
  /// In en, this message translates to:
  /// **'Turn on GPS so we can show your position on the map?'**
  String get enableGpsMessage;

  /// No description provided for @enableGps.
  ///
  /// In en, this message translates to:
  /// **'Turn on'**
  String get enableGps;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
