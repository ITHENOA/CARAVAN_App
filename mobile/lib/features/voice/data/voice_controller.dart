import 'dart:async';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import '../../../core/config/app_config.dart';
import '../../../core/constants/app_constants.dart';
import '../../../core/permissions/permission_service.dart';
import '../../../core/utils/app_logger.dart';
import '../../trip/data/trip_controller.dart';

enum PttUiState { idle, requesting, transmitting, listening, busy, unavailable }

class VoiceState {
  const VoiceState({
    this.ui = PttUiState.idle,
    this.peerStates = const {},
    this.error,
    this.latched = false,
  });

  final PttUiState ui;
  final Map<String, String> peerStates;
  final String? error;

  /// True after a short tap latch; stays live until tapped again.
  final bool latched;

  VoiceState copyWith({
    PttUiState? ui,
    Map<String, String>? peerStates,
    String? error,
    bool clearError = false,
    bool? latched,
  }) {
    return VoiceState(
      ui: ui ?? this.ui,
      peerStates: peerStates ?? this.peerStates,
      error: clearError ? null : (error ?? this.error),
      latched: latched ?? this.latched,
    );
  }
}

class VoiceController extends Notifier<VoiceState> {
  final Map<String, RTCPeerConnection> _peers = {};
  final Map<String, RTCVideoRenderer> _remoteRenderers = {};
  final Map<String, MediaStream> _remoteStreams = {};
  MediaStream? _local;
  bool _micLive = false;
  bool _wantLive = false;
  int _pressGeneration = 0;
  bool _drainingSignals = false;
  bool _audioConfigured = false;

  /// Renderers that must stay mounted (hidden RTCVideoView) for remote audio.
  List<RTCVideoRenderer> get remoteRenderers =>
      _remoteRenderers.values.toList(growable: false);

  int get remoteSinkCount => _remoteRenderers.length;

  @override
  VoiceState build() {
    ref.listen(webrtcSignalProvider, (prev, next) {
      if (next.isNotEmpty) unawaited(_drainSignals());
    });
    ref.listen(tripControllerProvider, (prev, next) {
      if (next.activeSpeakerId != null &&
          next.activeSpeakerId != next.clientId) {
        if (state.ui != PttUiState.transmitting &&
            state.ui != PttUiState.requesting) {
          state = state.copyWith(ui: PttUiState.listening);
        }
      } else if (next.activeSpeakerId == null &&
          state.ui == PttUiState.listening &&
          !state.latched) {
        state = state.copyWith(ui: PttUiState.idle);
      }
      if (prev?.joined == true && next.joined == false) {
        unawaited(disposeVoice());
      }
      for (final m in next.members) {
        if (m.id != next.clientId) {
          unawaited(_ensurePeer(m.id));
        }
      }
    });
    ref.onDispose(() {
      unawaited(disposeVoice());
    });
    return const VoiceState();
  }

  /// Short tap: latch on/off.
  Future<void> togglePtt() async {
    if (state.latched ||
        state.ui == PttUiState.transmitting ||
        state.ui == PttUiState.requesting) {
      await _endTransmit(clearLatch: true);
      return;
    }
    state = state.copyWith(latched: true);
    await _beginTransmit();
  }

  /// Hold start (ignore if already latched from tap).
  Future<void> beginMomentary() async {
    if (state.latched) return;
    await _beginTransmit();
  }

  /// Hold release — stop unless latched.
  Future<void> endMomentary() async {
    if (state.latched) return;
    await _endTransmit(clearLatch: false);
  }

  Future<void> _configureAudio() async {
    if (_audioConfigured) return;
    try {
      await Helper.setAndroidAudioConfiguration(
        AndroidAudioConfiguration.media,
      );
      await Helper.setSpeakerphoneOn(true);
      _audioConfigured = true;
    } catch (e, st) {
      AppLogger.d('audio config failed', e, st);
    }
  }

  Future<void> _beginTransmit() async {
    _wantLive = true;
    final gen = ++_pressGeneration;
    await _configureAudio();
    final micOk = await PermissionService().ensureMicrophone();
    if (!_wantLive || gen != _pressGeneration) return;
    if (!micOk) {
      state = state.copyWith(
        ui: PttUiState.unavailable,
        error: 'mic',
        latched: false,
      );
      return;
    }

    state = state.copyWith(ui: PttUiState.requesting, clearError: true);
    ref.read(tripControllerProvider.notifier).requestPtt();

    for (var i = 0; i < 40; i++) {
      await Future<void>.delayed(const Duration(milliseconds: 80));
      if (!_wantLive || gen != _pressGeneration) return;
      final trip = ref.read(tripControllerProvider);
      if (trip.activeSpeakerId == trip.clientId) {
        await _startMic();
        if (!_wantLive || gen != _pressGeneration) {
          await _stopMic();
          return;
        }
        if (_micLive) {
          state = state.copyWith(ui: PttUiState.transmitting, clearError: true);
        }
        return;
      }
      // Someone else talking is fine — keep waiting for our own grant.
    }
    if (_wantLive && gen == _pressGeneration) {
      state = state.copyWith(
        ui: PttUiState.unavailable,
        error: 'timeout',
        latched: false,
      );
      _wantLive = false;
    }
  }

  Future<void> _endTransmit({required bool clearLatch}) async {
    _wantLive = false;
    _pressGeneration++;
    if (clearLatch) {
      state = state.copyWith(latched: false);
    }
    ref.read(tripControllerProvider.notifier).releasePtt();
    await _stopMic();
    final trip = ref.read(tripControllerProvider);
    if (trip.activeSpeakerId != null &&
        trip.activeSpeakerId != trip.clientId) {
      state = state.copyWith(ui: PttUiState.listening, clearError: true);
    } else {
      state = state.copyWith(ui: PttUiState.idle, clearError: true);
    }
  }

  Future<void> _ensureLocalMic() async {
    if (_local != null) return;
    _local = await navigator.mediaDevices.getUserMedia({
      'audio': {
        'echoCancellation': true,
        'noiseSuppression': true,
        'autoGainControl': true,
        'channelCount': 1,
      },
      'video': false,
    });
    for (final track in _local!.getAudioTracks()) {
      track.enabled = false;
    }
  }

  Future<void> _startMic() async {
    try {
      await _ensureLocalMic();
      for (final track in _local!.getAudioTracks()) {
        track.enabled = true;
      }
      for (final entry in _peers.entries) {
        final pc = entry.value;
        final senders = await pc.getSenders();
        final audioSenders =
            senders.where((s) => s.track?.kind == 'audio').toList();
        if (audioSenders.isEmpty && _local != null) {
          for (final track in _local!.getAudioTracks()) {
            await pc.addTrack(track, _local!);
          }
          await _renegotiate(entry.key, pc);
        } else {
          final track = _local!.getAudioTracks().first;
          for (final s in audioSenders) {
            await s.replaceTrack(track);
            track.enabled = true;
          }
        }
      }
      _micLive = true;
      await Helper.setSpeakerphoneOn(true);
    } catch (e, st) {
      AppLogger.e('mic failed', e, st);
      state = state.copyWith(
        ui: PttUiState.unavailable,
        error: 'mic',
        latched: false,
      );
      _micLive = false;
      _wantLive = false;
    }
  }

  Future<void> _renegotiate(String peerId, RTCPeerConnection pc) async {
    try {
      final self = ref.read(tripControllerProvider).clientId ?? '';
      if (self.compareTo(peerId) >= 0) return;
      final offer = await pc.createOffer({
        'offerToReceiveAudio': 1,
        'offerToReceiveVideo': 0,
      });
      await pc.setLocalDescription(offer);
      ref.read(tripControllerProvider.notifier).sendSignal({
        'type': 'webrtc_offer',
        'version': AppConstants.protocolVersion,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
        'targetId': peerId,
        'sdp': offer.sdp,
      });
    } catch (e, st) {
      AppLogger.d('renegotiate failed', e, st);
    }
  }

  Future<void> _stopMic() async {
    if (_local != null) {
      for (final track in _local!.getAudioTracks()) {
        track.enabled = false;
      }
    }
    _micLive = false;
  }

  Future<void> _attachRemote(String peerId, MediaStream stream) async {
    try {
      await _configureAudio();
      var renderer = _remoteRenderers[peerId];
      if (renderer == null) {
        renderer = RTCVideoRenderer();
        await renderer.initialize();
        _remoteRenderers[peerId] = renderer;
        // Notify UI to mount hidden RTCVideoView.
        state = state.copyWith(peerStates: {
          ...state.peerStates,
          peerId: state.peerStates[peerId] ?? 'remote',
        });
      }
      _remoteStreams[peerId] = stream;
      renderer.srcObject = stream;
      for (final t in stream.getAudioTracks()) {
        t.enabled = true;
      }
      await Helper.setSpeakerphoneOn(true);
      AppLogger.d('playing remote audio from $peerId');
    } catch (e, st) {
      AppLogger.d('remote audio attach failed', e, st);
    }
  }

  Future<RTCPeerConnection> _ensurePeer(String peerId) async {
    if (_peers.containsKey(peerId)) return _peers[peerId]!;
    await _configureAudio();
    final config = {
      'iceServers': AppConfig.current.iceServers.map((e) => e.toMap()).toList(),
      'sdpSemantics': 'unified-plan',
    };
    final pc = await createPeerConnection(config);

    // Always have a sendrecv audio transceiver so remote can send to us.
    try {
      await pc.addTransceiver(
        kind: RTCRtpMediaType.RTCRtpMediaTypeAudio,
        init: RTCRtpTransceiverInit(
          direction: TransceiverDirection.SendRecv,
        ),
      );
    } catch (e, st) {
      AppLogger.d('addTransceiver failed', e, st);
    }

    try {
      await _ensureLocalMic();
      if (_local != null) {
        final senders = await pc.getSenders();
        final audioSenders =
            senders.where((s) => s.track == null || s.track?.kind == 'audio');
        if (audioSenders.isNotEmpty) {
          await audioSenders.first.replaceTrack(_local!.getAudioTracks().first);
        } else {
          for (final track in _local!.getAudioTracks()) {
            await pc.addTrack(track, _local!);
          }
        }
      }
    } catch (e, st) {
      AppLogger.d('pre-add mic tracks failed', e, st);
    }

    pc.onIceCandidate = (candidate) {
      if (candidate.candidate == null) return;
      ref.read(tripControllerProvider.notifier).sendSignal({
        'type': 'ice_candidate',
        'version': AppConstants.protocolVersion,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
        'targetId': peerId,
        'candidate': candidate.toMap(),
      });
    };
    pc.onConnectionState = (s) {
      final map = Map<String, String>.from(state.peerStates);
      map[peerId] = s.toString();
      state = state.copyWith(peerStates: map);
      if (s == RTCPeerConnectionState.RTCPeerConnectionStateFailed) {
        state = state.copyWith(ui: PttUiState.unavailable, error: 'ice');
      }
    };
    pc.onTrack = (event) {
      AppLogger.d('remote track from $peerId kind=${event.track.kind}');
      event.track.enabled = true;
      unawaited(() async {
        if (event.streams.isNotEmpty) {
          await _attachRemote(peerId, event.streams.first);
        } else {
          final stream = await createLocalMediaStream('remote_$peerId');
          await stream.addTrack(event.track);
          await _attachRemote(peerId, stream);
        }
      }());
    };

    _peers[peerId] = pc;

    final self = ref.read(tripControllerProvider).clientId ?? '';
    if (self.compareTo(peerId) < 0) {
      final offer = await pc.createOffer({
        'offerToReceiveAudio': 1,
        'offerToReceiveVideo': 0,
      });
      await pc.setLocalDescription(offer);
      ref.read(tripControllerProvider.notifier).sendSignal({
        'type': 'webrtc_offer',
        'version': AppConstants.protocolVersion,
        'timestamp': DateTime.now().millisecondsSinceEpoch,
        'targetId': peerId,
        'sdp': offer.sdp,
      });
    }
    return pc;
  }

  Future<void> _drainSignals() async {
    if (_drainingSignals) return;
    _drainingSignals = true;
    try {
      while (true) {
        final msg = ref.read(webrtcSignalProvider.notifier).take();
        if (msg == null) break;
        await _handleSignal(msg);
      }
    } finally {
      _drainingSignals = false;
      // New signals may have arrived while draining.
      if (ref.read(webrtcSignalProvider).isNotEmpty) {
        unawaited(_drainSignals());
      }
    }
  }

  Future<void> _handleSignal(Map<String, dynamic> raw) async {
    final from = raw['fromId'] as String?;
    if (from == null) return;
    final pc = await _ensurePeer(from);
    try {
      switch (raw['type']) {
        case 'webrtc_offer':
          await pc.setRemoteDescription(
            RTCSessionDescription(raw['sdp'] as String, 'offer'),
          );
          final answer = await pc.createAnswer();
          await pc.setLocalDescription(answer);
          ref.read(tripControllerProvider.notifier).sendSignal({
            'type': 'webrtc_answer',
            'version': AppConstants.protocolVersion,
            'timestamp': DateTime.now().millisecondsSinceEpoch,
            'targetId': from,
            'sdp': answer.sdp,
          });
        case 'webrtc_answer':
          await pc.setRemoteDescription(
            RTCSessionDescription(raw['sdp'] as String, 'answer'),
          );
        case 'ice_candidate':
          final c = Map<String, dynamic>.from(
            raw['candidate'] as Map,
          );
          await pc.addCandidate(
            RTCIceCandidate(
              c['candidate'] as String?,
              c['sdpMid'] as String?,
              c['sdpMLineIndex'] as int?,
            ),
          );
      }
    } catch (e, st) {
      AppLogger.d('signal handling failed', e, st);
    }
  }

  Future<void> disposeVoice() async {
    _wantLive = false;
    _pressGeneration++;
    await _stopMic();
    for (final pc in _peers.values) {
      await pc.close();
    }
    _peers.clear();
    for (final r in _remoteRenderers.values) {
      r.srcObject = null;
      await r.dispose();
    }
    _remoteRenderers.clear();
    _remoteStreams.clear();
    await _local?.dispose();
    _local = null;
    state = const VoiceState();
  }
}

final voiceControllerProvider = NotifierProvider<VoiceController, VoiceState>(
  VoiceController.new,
);
