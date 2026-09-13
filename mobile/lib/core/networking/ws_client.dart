import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:web_socket_channel/web_socket_channel.dart';

import '../constants/app_constants.dart';
import '../utils/app_logger.dart';

enum WsConnectionState { disconnected, connecting, connected, reconnecting }

typedef JsonHandler = void Function(Map<String, dynamic> message);

/// WebSocket client with exponential backoff + jitter reconnect.
class CaravanWsClient {
  CaravanWsClient({required this.urlBuilder, this.onMessage, this.onState});

  final String Function() urlBuilder;
  JsonHandler? onMessage;
  void Function(WsConnectionState state)? onState;

  WebSocketChannel? _channel;
  StreamSubscription<dynamic>? _sub;
  Timer? _reconnectTimer;
  Timer? _pingTimer;
  int _attempt = 0;
  bool _manualClose = false;
  WsConnectionState _state = WsConnectionState.disconnected;

  WsConnectionState get state => _state;

  void connect() {
    _manualClose = false;
    _open();
  }

  Future<void> close() async {
    _manualClose = true;
    _reconnectTimer?.cancel();
    _pingTimer?.cancel();
    await _sub?.cancel();
    await _channel?.sink.close();
    _setState(WsConnectionState.disconnected);
  }

  void send(Map<String, dynamic> message) {
    final encoded = jsonEncode(message);
    if (encoded.length > AppConstants.maxPayloadBytes) {
      AppLogger.e('Outbound payload too large');
      return;
    }
    _channel?.sink.add(encoded);
  }

  void _open() {
    _reconnectTimer?.cancel();
    _setState(
      _attempt == 0
          ? WsConnectionState.connecting
          : WsConnectionState.reconnecting,
    );
    try {
      final uri = Uri.parse(urlBuilder());
      _channel = WebSocketChannel.connect(uri);
      _sub = _channel!.stream.listen(
        _onData,
        onError: (Object e, StackTrace st) {
          AppLogger.d('WS error', e, st);
          _scheduleReconnect();
        },
        onDone: _scheduleReconnect,
        cancelOnError: true,
      );
      _setState(WsConnectionState.connected);
      _attempt = 0;
      _startPing();
    } catch (e, st) {
      AppLogger.d('WS connect failed', e, st);
      _scheduleReconnect();
    }
  }

  void _onData(dynamic data) {
    try {
      final decoded = jsonDecode(
        String.fromCharCodes(
          data is String ? data.codeUnits : (data as List<int>),
        ),
      );
      if (decoded is Map<String, dynamic>) {
        onMessage?.call(decoded);
      } else if (decoded is Map) {
        onMessage?.call(Map<String, dynamic>.from(decoded));
      }
    } catch (e, st) {
      AppLogger.d('Bad WS frame', e, st);
    }
  }

  void _scheduleReconnect() {
    if (_manualClose) return;
    _pingTimer?.cancel();
    _setState(WsConnectionState.reconnecting);
    final exp = min(8, _attempt);
    final baseMs = (1000 * pow(2, exp)).toInt();
    final jitter = Random().nextInt(400);
    final delay = Duration(milliseconds: min(30000, baseMs + jitter));
    _attempt++;
    _reconnectTimer?.cancel();
    _reconnectTimer = Timer(delay, _open);
  }

  void _startPing() {
    _pingTimer?.cancel();
    _pingTimer = Timer.periodic(const Duration(seconds: 25), (_) {
      send({
        'type': 'ping',
        'version': AppConstants.protocolVersion,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
      });
    });
  }

  void _setState(WsConnectionState s) {
    _state = s;
    onState?.call(s);
  }

  /// Visible for unit tests.
  static Duration computeBackoff(int attempt, {int Function()? randomMs}) {
    final exp = min(8, attempt);
    final baseMs = (1000 * pow(2, exp)).toInt();
    final jitter = randomMs?.call() ?? 0;
    return Duration(milliseconds: min(30000, baseMs + jitter));
  }
}
