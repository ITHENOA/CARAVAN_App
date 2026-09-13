import '../../../core/utils/geo.dart';

/// Distinct convoy colors — assigned per client so pins stay unique.
const convoyColorPalette = <String>[
  '#0EA5E9',
  '#F59E0B',
  '#10B981',
  '#EF4444',
  '#8B5CF6',
  '#EC4899',
  '#14B8A6',
  '#F97316',
  '#6366F1',
  '#84CC16',
  '#06B6D4',
  '#D946EF',
];

String normalizeHexColor(String? raw) {
  if (raw == null || raw.isEmpty) return '';
  var h = raw.trim();
  if (!h.startsWith('#')) h = '#$h';
  if (h.length == 4) {
    // #RGB → #RRGGBB
    h = '#${h[1]}${h[1]}${h[2]}${h[2]}${h[3]}${h[3]}';
  }
  return h.toUpperCase();
}

String distinctColorFor(
  String clientId, {
  String? preferred,
  Iterable<String> taken = const [],
}) {
  final takenSet = taken.map(normalizeHexColor).where((e) => e.isNotEmpty).toSet();
  final pref = normalizeHexColor(preferred);
  if (pref.isNotEmpty && !takenSet.contains(pref)) return pref;

  final ordered = List<String>.from(convoyColorPalette);
  ordered.sort((a, b) {
    final ha = (a.hashCode ^ clientId.hashCode).abs();
    final hb = (b.hashCode ^ clientId.hashCode).abs();
    return ha.compareTo(hb);
  });
  for (final c in ordered) {
    if (!takenSet.contains(c.toUpperCase())) return c;
  }
  return convoyColorPalette[clientId.hashCode.abs() % convoyColorPalette.length];
}

String convoySpeedColorHex(double? speedMps) {
  if (speedMps == null || speedMps.isNaN) return '#9E9E9E';
  final kmh = speedMps * 3.6;
  if (kmh < 12) return '#E53935';
  if (kmh < 35) return '#FB8C00';
  return '#43A047';
}

String formatEta(double durationSeconds) {
  final mins = (durationSeconds / 60).round();
  if (mins < 60) return '$mins min';
  final h = mins ~/ 60;
  final m = mins % 60;
  return m == 0 ? '${h}h' : '${h}h ${m}m';
}

String formatRouteDistance(double meters, {bool metric = true}) =>
    formatDistanceMeters(meters, metric: metric);
