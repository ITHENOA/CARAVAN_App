import 'package:flutter_test/flutter_test.dart';

import 'package:caravan/core/utils/geo.dart';

void main() {
  test('haversine known short distance', () {
    const a = LatLng(35.6892, 51.3890);
    const b = LatLng(35.6900, 51.3890);
    final d = haversineMeters(a, b);
    expect(d, greaterThan(70));
    expect(d, lessThan(120));
  });

  test('ahead/behind via destination projection', () {
    const self = LatLng(0, 0);
    const otherAhead = LatLng(0.01, 0);
    const dest = LatLng(0.02, 0);
    final rel = relatePositions(
      self: self,
      other: otherAhead,
      destination: dest,
    );
    expect(rel.bearing, RelativeBearing.ahead);
  });

  test('format metric', () {
    expect(formatDistanceMeters(320), '320 m');
    expect(formatDistanceMeters(1400), '1.4 km');
  });
}
