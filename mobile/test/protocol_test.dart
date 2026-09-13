import 'package:flutter_test/flutter_test.dart';

import 'package:caravan/core/utils/geo.dart';
import 'package:caravan/features/trip/domain/protocol.dart';

void main() {
  test('rejects invalid coordinates in location builder', () {
    expect(
      () => buildLocationUpdate(latitude: 999, longitude: 0),
      throwsArgumentError,
    );
  });

  test('parses protocol message', () {
    final msg = ProtocolMessage.tryParse({
      'type': 'pong',
      'version': 1,
      'timestamp': 123,
    });
    expect(msg?.type, 'pong');
  });

  test('rejects bad protocol version', () {
    final msg = ProtocolMessage.tryParse({
      'type': 'pong',
      'version': 99,
      'timestamp': 123,
    });
    expect(msg, isNull);
  });

  test('isValidLatLng', () {
    expect(isValidLatLng(35, 51), isTrue);
    expect(isValidLatLng(double.nan, 0), isFalse);
  });
}
