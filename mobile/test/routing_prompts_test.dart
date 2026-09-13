import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import 'package:caravan/core/utils/convoy_colors.dart';
import 'package:caravan/features/chat/data/quick_prompts_controller.dart';
import 'package:caravan/features/destination/data/neshan_routing_service.dart';
import 'package:caravan/features/destination/data/routing_service.dart';

void main() {
  test('OSRM body parser extracts polyline and ETA', () {
    const body = '''
{
  "code": "Ok",
  "routes": [{
    "distance": 1234.5,
    "duration": 300.0,
    "geometry": {
      "type": "LineString",
      "coordinates": [[51.4, 35.7], [51.41, 35.71]]
    }
  }]
}
''';
    final route = RoutingService.parseOsrmBody(body);
    expect(route, isNotNull);
    expect(route!.points.length, 2);
    expect(route.distanceMeters, 1234.5);
    expect(route.durationSeconds, 300);
    expect(route.etaLabel, '5 min');
    expect(route.segments, isEmpty);
  });

  test('Neshan body parser colors steps from implied speed', () {
    // Encoded polyline from a live Neshan step (Tehran sample).
    final poly = String.fromCharCodes([
      99, 121, 123, 120, 69, 97, 123, 115, 120, 72, 65, 107, 66, 65, 109, 66,
      68, 97, 64, 66, 75, 72, 115, 64, 66, 87, 68, 93, 74, 123, 64,
    ]);
    final body = jsonEncode({
      'routes': [
        {
          'overview_polyline': {'points': poly},
          'legs': [
            {
              'summary': 'test',
              'distance': {'value': 300.0, 'text': '300 m'},
              'duration': {'value': 160.0, 'text': '3 min'},
              'steps': [
                {
                  'name': 'fast',
                  'distance': {'value': 200.0, 'text': '200 m'},
                  'duration': {'value': 10.0, 'text': '10 s'},
                  'polyline': poly,
                  'type': 'depart',
                },
                {
                  'name': 'slow',
                  'distance': {'value': 100.0, 'text': '100 m'},
                  'duration': {'value': 50.0, 'text': '50 s'},
                  'polyline': poly,
                  'type': 'arrive',
                },
              ],
            },
          ],
        },
      ],
    });
    final route = NeshanRoutingService.parseNeshanBody(body);
    expect(route, isNotNull);
    expect(route!.distanceMeters, 300.0);
    expect(route.durationSeconds, 160.0);
    expect(
      route.segments.map((s) => s.colorHex).toList(),
      ['#43A047', '#E53935'],
    );
    expect(route.points.length, greaterThanOrEqualTo(2));
  });

  test('Neshan parser returns null on invalid JSON and empty routes', () {
    expect(NeshanRoutingService.parseNeshanBody('not-json'), isNull);
    expect(NeshanRoutingService.parseNeshanBody('{"routes":[]}'), isNull);
  });

  test('default quick prompts are English convoy phrases', () {
    expect(defaultQuickPrompts, contains('Wait'));
    expect(defaultQuickPrompts, isNot(contains('Go to UK')));
  });

  test('convoy speed colors map slow traffic to red', () {
    expect(convoySpeedColorHex(1), '#E53935');
    expect(convoySpeedColorHex(5), '#FB8C00');
    expect(convoySpeedColorHex(20), '#43A047');
  });
}
