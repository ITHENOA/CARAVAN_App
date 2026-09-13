import 'dart:math' as math;

class LatLng {
  const LatLng(this.latitude, this.longitude);
  final double latitude;
  final double longitude;
}

bool isValidLatLng(double lat, double lng) {
  if (lat.isNaN || lng.isNaN || lat.isInfinite || lng.isInfinite) return false;
  if (lat < -90 || lat > 90) return false;
  if (lng < -180 || lng > 180) return false;
  return true;
}

/// Haversine distance in meters.
double haversineMeters(LatLng a, LatLng b) {
  const r = 6371000.0;
  final dLat = _rad(b.latitude - a.latitude);
  final dLon = _rad(b.longitude - a.longitude);
  final lat1 = _rad(a.latitude);
  final lat2 = _rad(b.latitude);
  final h =
      math.sin(dLat / 2) * math.sin(dLat / 2) +
      math.cos(lat1) * math.cos(lat2) * math.sin(dLon / 2) * math.sin(dLon / 2);
  return 2 * r * math.asin(math.min(1, math.sqrt(h)));
}

double _rad(double deg) => deg * math.pi / 180;

enum RelativeBearing { ahead, behind, away }

class DistanceRelation {
  const DistanceRelation({required this.meters, required this.bearing});
  final double meters;
  final RelativeBearing bearing;
}

/// Prefer route-projection when available; else heading-based local projection.
DistanceRelation relatePositions({
  required LatLng self,
  required LatLng other,
  double? selfHeadingDegrees,
  LatLng? destination,
}) {
  final meters = haversineMeters(self, other);
  if (destination != null) {
    final selfToDest = haversineMeters(self, destination);
    final otherToDest = haversineMeters(other, destination);
    final delta = otherToDest - selfToDest;
    if (delta.abs() > 25) {
      return DistanceRelation(
        meters: meters,
        bearing: delta > 0 ? RelativeBearing.behind : RelativeBearing.ahead,
      );
    }
  }
  if (selfHeadingDegrees != null && !selfHeadingDegrees.isNaN) {
    final bearingToOther = initialBearing(self, other);
    final delta = _normalizeAngle(bearingToOther - selfHeadingDegrees);
    if (delta.abs() <= 60) {
      return DistanceRelation(meters: meters, bearing: RelativeBearing.ahead);
    }
    if (delta.abs() >= 120) {
      return DistanceRelation(meters: meters, bearing: RelativeBearing.behind);
    }
  }
  return DistanceRelation(meters: meters, bearing: RelativeBearing.away);
}

double initialBearing(LatLng a, LatLng b) {
  final lat1 = _rad(a.latitude);
  final lat2 = _rad(b.latitude);
  final dLon = _rad(b.longitude - a.longitude);
  final y = math.sin(dLon) * math.cos(lat2);
  final x =
      math.cos(lat1) * math.sin(lat2) -
      math.sin(lat1) * math.cos(lat2) * math.cos(dLon);
  return (_deg(math.atan2(y, x)) + 360) % 360;
}

double _deg(double rad) => rad * 180 / math.pi;

double _normalizeAngle(double deg) {
  var d = (deg + 180) % 360;
  if (d < 0) d += 360;
  return d - 180;
}

String formatDistanceMeters(double meters, {bool metric = true}) {
  if (metric) {
    if (meters < 1000) return '${meters.round()} m';
    return '${(meters / 1000).toStringAsFixed(meters < 10000 ? 1 : 0)} km';
  }
  final feet = meters * 3.28084;
  if (feet < 1000) return '${feet.round()} ft';
  final miles = meters / 1609.344;
  return '${miles.toStringAsFixed(miles < 10 ? 1 : 0)} mi';
}
