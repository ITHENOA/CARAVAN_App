import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';

/// Classic teardrop pins + self/nav markers for MapLibre [addImage].
class MapMarkerIcons {
  MapMarkerIcons._();

  static final Map<String, Uint8List> _pinCache = {};

  static Color parseHex(String hex) {
    final clean = hex.replaceAll('#', '');
    final value = int.parse(clean.length == 6 ? 'FF$clean' : clean, radix: 16);
    return Color(value);
  }

  static String pinImageId(String hex) {
    final clean = hex.replaceAll('#', '').toUpperCase();
    return 'pin_$clean';
  }

  static const navArrowId = 'nav_arrow_self';
  static const selfDotId = 'self_dot_heading';
  static const destinationPinId = 'pin_DEST_RED';

  /// Destination pin — classic red Google-style teardrop.
  static const destinationRed = Color(0xFFE53935);

  static Future<Uint8List> pinPng(Color color) async {
    final key =
        '${(color.a * 255).round().toRadixString(16)}'
        '${(color.r * 255).round().toRadixString(16)}'
        '${(color.g * 255).round().toRadixString(16)}'
        '${(color.b * 255).round().toRadixString(16)}';
    final cached = _pinCache[key];
    if (cached != null) return cached;

    const w = 96;
    const h = 128;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(
      recorder,
      Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()),
    );

    // Soft ground shadow.
    canvas.drawOval(
      Rect.fromCenter(center: const Offset(48, 122), width: 28, height: 9),
      Paint()..color = const Color(0x55000000),
    );

    // Teardrop path (rounded head → sharp tip).
    final pin = Path()
      ..moveTo(48, 118)
      ..cubicTo(48, 118, 18, 72, 18, 44)
      ..cubicTo(18, 24, 31, 10, 48, 10)
      ..cubicTo(65, 10, 78, 24, 78, 44)
      ..cubicTo(78, 72, 48, 118, 48, 118)
      ..close();

    canvas.drawPath(
      pin,
      Paint()
        ..color = color
        ..style = PaintingStyle.fill
        ..isAntiAlias = true,
    );

    // Inner darker disc (destination-style hole).
    canvas.drawCircle(
      const Offset(48, 42),
      13,
      Paint()
        ..color = Color.lerp(color, const Color(0xFF000000), 0.38)!
        ..isAntiAlias = true,
    );

    final picture = recorder.endRecording();
    final image = await picture.toImage(w, h);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    final out = bytes!.buffer.asUint8List();
    _pinCache[key] = out;
    return out;
  }

  static Future<Uint8List> destinationPinPng() => pinPng(destinationRed);

  /// Idle self marker: blue accuracy halo + white ring + heading cone (up = 0°).
  static Future<Uint8List> selfDotWithHeadingPng() async {
    const w = 128;
    const h = 128;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(
      recorder,
      Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()),
    );
    const center = Offset(64, 72);

    // Accuracy halo
    canvas.drawCircle(
      center,
      36,
      Paint()
        ..color = const Color(0x550EA5E9)
        ..isAntiAlias = true,
    );

    // Heading cone (pointing up)
    final cone = Path()
      ..moveTo(center.dx, center.dy - 52)
      ..lineTo(center.dx - 28, center.dy + 4)
      ..quadraticBezierTo(center.dx, center.dy - 6, center.dx + 28, center.dy + 4)
      ..close();
    canvas.drawPath(
      cone,
      Paint()
        ..color = const Color(0x880EA5E9)
        ..isAntiAlias = true,
    );

    // White ring + blue core
    canvas.drawCircle(
      center,
      14,
      Paint()
        ..color = Colors.white
        ..isAntiAlias = true,
    );
    canvas.drawCircle(
      center,
      10,
      Paint()
        ..color = const Color(0xFF0EA5E9)
        ..isAntiAlias = true,
    );

    final picture = recorder.endRecording();
    final image = await picture.toImage(w, h);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    return bytes!.buffer.asUint8List();
  }

  /// Driving marker: yellow triangle on white base with soft shadow.
  static Future<Uint8List> navArrowPng({
    Color fill = const Color(0xFFFBBF24),
  }) async {
    const w = 96;
    const h = 96;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(
      recorder,
      Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()),
    );

    // Soft circular shadow under the arrow.
    canvas.drawOval(
      Rect.fromCenter(center: const Offset(48, 78), width: 42, height: 14),
      Paint()..color = const Color(0x66000000),
    );

    // Equilateral-ish triangle pointing up, rounded feel via thick white stroke.
    final path = Path()
      ..moveTo(48, 12)
      ..lineTo(82, 78)
      ..lineTo(14, 78)
      ..close();

    canvas.drawPath(
      path,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 14
        ..strokeJoin = StrokeJoin.round
        ..strokeCap = StrokeCap.round
        ..isAntiAlias = true,
    );
    // Fill white base slightly larger for 3D plate look.
    canvas.drawPath(
      path,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.fill
        ..isAntiAlias = true,
    );

    // Inner yellow face (slightly inset).
    final inner = Path()
      ..moveTo(48, 22)
      ..lineTo(74, 72)
      ..lineTo(22, 72)
      ..close();
    canvas.drawPath(
      inner,
      Paint()
        ..color = fill
        ..style = PaintingStyle.fill
        ..isAntiAlias = true,
    );

    final picture = recorder.endRecording();
    final image = await picture.toImage(w, h);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    return bytes!.buffer.asUint8List();
  }
}
