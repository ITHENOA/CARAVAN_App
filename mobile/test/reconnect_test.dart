import 'package:flutter_test/flutter_test.dart';

import 'package:caravan/core/networking/ws_client.dart';

void main() {
  test('backoff grows then caps', () {
    expect(
      CaravanWsClient.computeBackoff(0, randomMs: () => 0),
      const Duration(seconds: 1),
    );
    expect(
      CaravanWsClient.computeBackoff(1, randomMs: () => 0),
      const Duration(seconds: 2),
    );
    expect(
      CaravanWsClient.computeBackoff(2, randomMs: () => 0),
      const Duration(seconds: 4),
    );
    expect(
      CaravanWsClient.computeBackoff(10, randomMs: () => 0).inMilliseconds,
      lessThanOrEqualTo(30000),
    );
  });
}
