package com.example.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

enum class PttState {
    IDLE,
    REQUESTING,
    TRANSMITTING,
    BUSY
}

class VoicePttController(private val context: Context) {
    private val _pttState = MutableStateFlow(PttState.IDLE)
    val pttState: StateFlow<PttState> = _pttState.asStateFlow()

    private val _activeSpeakerName = MutableStateFlow<String?>(null)
    val activeSpeakerName: StateFlow<String?> = _activeSpeakerName.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _voiceNoteRecording = MutableStateFlow(false)
    val voiceNoteRecording: StateFlow<Boolean> = _voiceNoteRecording.asStateFlow()

    private val _mutedPeerIds = MutableStateFlow<Set<String>>(emptySet())
    val mutedPeerIds: StateFlow<Set<String>> = _mutedPeerIds.asStateFlow()

    /** ClientId of the remote speaker shown in the preview chip (name alone can mismatch). */
    private var activeSpeakerClientId: String? = null
    private var speakingClearJob: Job? = null

    // Callback invoked when a chunk of raw 16-bit PCM audio is captured from the mic
    var onAudioChunkCaptured: ((ByteArray) -> Unit)? = null
    /** Fired when live transmission completes flushing all audio chunks. */
    var onLiveTransmissionEnded: (() -> Unit)? = null
    /** Fired when voice-note hits max length so the VM can flush/send. */
    var onVoiceNoteAutoStop: (() -> Unit)? = null
    /** When false, PTT chirps stay silent (haptics still run). */
    var isSoundEnabled: () -> Boolean = { true }
    var isHapticsEnabled: () -> Boolean = { true }

    private var toneGenerator: ToneGenerator? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var voiceNoteBuffer: ByteArrayOutputStream? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Jitter-Buffered Audio Playback Queue to completely eliminate audio cutouts & clicks
    private val playbackQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    private var playbackJob: Job? = null
    @Volatile
    private var currentSampleRate = 16000

    companion object {
        private const val SAMPLE_RATE = 16000
        /** Cap ~30s so buffered notes stay sendable over WS. */
        private const val MAX_VOICE_NOTE_BYTES = SAMPLE_RATE * 2 * 30
        /** Clear stuck "X is talking" if audio/ptt_release stops arriving. */
        private const val SPEAKING_IDLE_MS = 1_800L
    }

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (_: Exception) {}
    }

    fun playChirpStart() {
        if (isSoundEnabled()) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 130)
            } catch (_: Exception) {}
        }
        if (isHapticsEnabled()) vibrate(80)
    }

    fun playChirpEnd() {
        if (isSoundEnabled()) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 90)
            } catch (_: Exception) {}
        }
        if (isHapticsEnabled()) vibrate(50)
    }

    fun playFloorAlert() {
        if (isSoundEnabled()) {
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)
            } catch (_: Exception) {}
        }
        if (isHapticsEnabled()) vibrate(60)
    }

    fun onRequestPtt() {
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) return
        _pttState.value = PttState.REQUESTING
    }

    fun onFloorGranted() {
        // Instant open-mic: ignore duplicate grants while already live (no request queue).
        if (_pttState.value == PttState.TRANSMITTING) return
        if (_voiceNoteRecording.value) return
        speakingClearJob?.cancel()
        speakingClearJob = null
        activeSpeakerClientId = null
        _pttState.value = PttState.TRANSMITTING
        _activeSpeakerName.value = "You"
        playChirpStart()
        startAudioCapture(liveStream = true)
    }

    /** Hold/toggle voice note: record locally, then flush PCM via [stopVoiceNote]. */
    fun startVoiceNote() {
        if (_voiceNoteRecording.value) return
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) return
        _voiceNoteRecording.value = true
        voiceNoteBuffer = ByteArrayOutputStream()
        playChirpStart()
        startAudioCapture(liveStream = false)
    }

    /** Stops voice-note capture and returns PCM bytes (empty if too short / cancelled). */
    suspend fun stopVoiceNote(): ByteArray {
        if (!_voiceNoteRecording.value && voiceNoteBuffer == null) return ByteArray(0)
        _voiceNoteRecording.value = false
        // Stop the mic so read() unblocks, then join — cancel() was dropping the buffer.
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
        } catch (_: Exception) {}
        withTimeoutOrNull(1500L) { recordingJob?.join() }
        recordingJob?.cancel()
        recordingJob = null
        cleanUpAudioRecord()
        _audioAmplitude.value = 0f

        val pcm = voiceNoteBuffer?.toByteArray() ?: ByteArray(0)
        voiceNoteBuffer = null
        playChirpEnd()
        Log.d("VoicePttController", "Voice note captured ${pcm.size} bytes")
        // ~80ms minimum
        return if (pcm.size < SAMPLE_RATE * 2 / 12) ByteArray(0) else pcm
    }

    /** Someone else started talking — show UI, but never block local PTT. */
    fun onRemoteSpeaking(speakerName: String, clientId: String? = null) {
        noteRemoteSpeaking(speakerName, clientId)
        if (_pttState.value != PttState.TRANSMITTING && _pttState.value != PttState.REQUESTING) {
            playFloorAlert()
        }
    }

    /** Update speaking label from live audio without chirping on every chunk. */
    fun noteRemoteSpeaking(speakerName: String, clientId: String? = null) {
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) {
            return
        }
        if (clientId != null) activeSpeakerClientId = clientId
        if (_activeSpeakerName.value != speakerName) {
            _activeSpeakerName.value = speakerName
        }
        if (_pttState.value == PttState.BUSY) {
            _pttState.value = PttState.IDLE
        }
        // Audio can arrive without a matching ptt_release (name mismatch / dropped msg).
        scheduleSpeakingClear()
    }

    fun onRemoteStopped(speakerName: String? = null, clientId: String? = null) {
        if (_pttState.value == PttState.TRANSMITTING || _pttState.value == PttState.REQUESTING) {
            return
        }
        // Always clear — name-only matching left the chip stuck when displayName drifted.
        clearRemoteSpeaker()
        if (_pttState.value == PttState.BUSY) {
            _pttState.value = PttState.IDLE
        }
    }

    fun togglePeerMute(peerId: String) {
        _mutedPeerIds.value = _mutedPeerIds.value.toMutableSet().also { muted ->
            if (!muted.add(peerId)) muted.remove(peerId)
        }
    }

    fun setAllPeersMuted(peerIds: Iterable<String>, muted: Boolean) {
        _mutedPeerIds.value = _mutedPeerIds.value.toMutableSet().also { current ->
            peerIds.forEach { peerId ->
                if (muted) current.add(peerId) else current.remove(peerId)
            }
        }
    }

    fun onFloorReleased() {
        val wasTransmitting = _pttState.value == PttState.TRANSMITTING
        _pttState.value = PttState.IDLE
        clearRemoteSpeaker()
        if (wasTransmitting) {
            playChirpEnd()
        }
        // Allow in-flight audio buffer to flush and notify VM when transmission is completely finished
        scope.launch {
            kotlinx.coroutines.delay(100L)
            stopAudioCapture()
            if (wasTransmitting) {
                onLiveTransmissionEnded?.invoke()
            }
        }
    }

    private fun scheduleSpeakingClear() {
        speakingClearJob?.cancel()
        speakingClearJob = scope.launch {
            delay(SPEAKING_IDLE_MS)
            if (_pttState.value != PttState.TRANSMITTING && _pttState.value != PttState.REQUESTING) {
                clearRemoteSpeaker()
            }
        }
    }

    private fun clearRemoteSpeaker() {
        speakingClearJob?.cancel()
        speakingClearJob = null
        activeSpeakerClientId = null
        _activeSpeakerName.value = null
    }

    /**
     * Streams incoming raw PCM 16-bit mono audio data from another convoy member directly through AudioTrack.
     * Uses a resilient jitter buffer to prevent choppy audio, underruns, and cutouts.
     */
    fun playAudioChunk(pcmData: ByteArray, sampleRate: Int = 16000, speakerId: String? = null) {
        if (pcmData.isEmpty()) return
        if (speakerId != null && _mutedPeerIds.value.contains(speakerId)) return

        currentSampleRate = sampleRate
        playbackQueue.offer(pcmData)

        synchronized(this) {
            if (playbackJob == null || playbackJob?.isActive != true) {
                playbackJob = scope.launch(Dispatchers.IO) {
                    runPlaybackLoop()
                }
            }
        }
    }

    private suspend fun runPlaybackLoop() {
        var track: AudioTrack? = null
        try {
            // Ensure media stream has audible volume if turned down
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (am != null) {
                    val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    if (curVol <= 1 && maxVol > 0) {
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxVol * 0.7f).toInt(), 0)
                    }
                }
            } catch (_: Exception) {}

            val sampleRate = currentSampleRate
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize * 2, 8192) else 8192

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.e("VoicePttController", "AudioTrack failed to initialize! State: ${track.state}")
                track.release()
                return
            }

            audioTrack = track
            track.setVolume(1.0f)
            track.play()

            var idleCount = 0
            while (currentCoroutineContext().isActive) {
                val chunk = playbackQueue.poll()
                if (chunk != null && chunk.isNotEmpty()) {
                    idleCount = 0
                    var writtenTotal = 0
                    while (writtenTotal < chunk.size && currentCoroutineContext().isActive) {
                        val written = track.write(
                            chunk,
                            writtenTotal,
                            chunk.size - writtenTotal,
                            AudioTrack.WRITE_BLOCKING
                        )
                        if (written <= 0) {
                            Log.w("VoicePttController", "AudioTrack.write returned $written")
                            break
                        }
                        writtenTotal += written
                    }
                } else {
                    idleCount++
                    // Wait up to ~1.2 seconds of silence before tearing down the track
                    if (idleCount > 60) {
                        break
                    }
                    delay(20L)
                }
            }
        } catch (e: Exception) {
            Log.e("VoicePttController", "Error in audio playback loop", e)
        } finally {
            try {
                // Give AudioFlinger a moment to render the tail of the buffer
                delay(120L)
                track?.let {
                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        it.stop()
                    }
                    it.release()
                }
            } catch (_: Exception) {}
            if (audioTrack == track) {
                audioTrack = null
            }
        }
    }

    private fun cleanUpAudioTrack() {
        playbackJob?.cancel()
        playbackJob = null
        playbackQueue.clear()
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {}
        audioTrack = null
    }

    private fun startAudioCapture(liveStream: Boolean) {
        stopAudioCapture()
        recordingJob = scope.launch {
            var hitVoiceNoteMax = false
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Log.e("VoicePttController", "RECORD_AUDIO not granted — PTT indicator will show on peers but no audio is sent")
                    return@launch
                }

                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
                if (minBufferSize <= 0) {
                    Log.e("VoicePttController", "AudioRecord unsupported at ${SAMPLE_RATE}Hz (minBuffer=$minBufferSize)")
                    return@launch
                }
                // Chunks of ~128ms (2048 shorts = 4096 bytes)
                val bufferSize = maxOf(minBufferSize, 4096)

                var rec: AudioRecord? = null
                try {
                    rec = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                } catch (_: Exception) {}
                if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                    try { rec?.release() } catch (_: Exception) {}
                    rec = AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )
                }
                audioRecord = rec

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("VoicePttController", "AudioRecord failed to initialize — no audio chunks will be sent")
                    cleanUpAudioRecord()
                    return@launch
                }

                audioRecord?.startRecording()
                Log.d("VoicePttController", if (liveStream) "PTT capture started" else "Voice-note capture started")
                val buffer = ShortArray(1024) // 64ms per chunk for responsive, zero-clip start
                while (isActive && (
                    (liveStream && _pttState.value == PttState.TRANSMITTING) ||
                        (!liveStream && _voiceNoteRecording.value)
                    )
                ) {
                    val readShorts = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readShorts > 0) {
                        // Compute RMS for UI visual wave animation
                        var sum = 0.0
                        for (i in 0 until readShorts) {
                            val s = buffer[i]
                            sum += s * s
                        }
                        val rms = sqrt(sum / readShorts)
                        val normalized = (rms / 25000.0).toFloat().coerceIn(0.05f, 1f)
                        _audioAmplitude.value = normalized

                        // Convert 16-bit PCM ShortArray to Little-Endian ByteArray
                        val byteData = ByteArray(readShorts * 2)
                        var idx = 0
                        for (i in 0 until readShorts) {
                            val s = buffer[i].toInt()
                            byteData[idx++] = (s and 0xFF).toByte()
                            byteData[idx++] = ((s shr 8) and 0xFF).toByte()
                        }

                        if (liveStream) {
                            onAudioChunkCaptured?.invoke(byteData)
                        } else {
                            val buf = voiceNoteBuffer ?: break
                            val room = MAX_VOICE_NOTE_BYTES - buf.size()
                            if (room <= 0) {
                                hitVoiceNoteMax = true
                                _voiceNoteRecording.value = false
                                break
                            }
                            if (byteData.size > room) {
                                buf.write(byteData, 0, room)
                                hitVoiceNoteMax = true
                                _voiceNoteRecording.value = false
                                break
                            }
                            buf.write(byteData)
                        }
                    } else if (readShorts < 0) {
                        Log.e("VoicePttController", "AudioRecord.read error: $readShorts")
                        break
                    }
                }

                // Drain any final pending audio samples from the hardware buffer so word endings are never cut off
                if (liveStream) {
                    try {
                        val tailBuffer = ShortArray(1024)
                        val tailRead = audioRecord?.read(tailBuffer, 0, tailBuffer.size, AudioRecord.READ_NON_BLOCKING) ?: 0
                        if (tailRead > 0) {
                            val byteData = ByteArray(tailRead * 2)
                            var idx = 0
                            for (i in 0 until tailRead) {
                                val s = tailBuffer[i].toInt()
                                byteData[idx++] = (s and 0xFF).toByte()
                                byteData[idx++] = ((s shr 8) and 0xFF).toByte()
                            }
                            onAudioChunkCaptured?.invoke(byteData)
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e("VoicePttController", "Audio recording error", e)
            } finally {
                cleanUpAudioRecord()
                _audioAmplitude.value = 0f
            }
            // After capture fully stops — avoids join() deadlock with auto-stop flush
            if (hitVoiceNoteMax) {
                onVoiceNoteAutoStop?.invoke()
            }
        }
    }

    private fun stopAudioCapture() {
        recordingJob?.cancel()
        recordingJob = null
        cleanUpAudioRecord()
        _audioAmplitude.value = 0f
    }

    private fun cleanUpAudioRecord() {
        try {
            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.stop()
            }
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    @Suppress("DEPRECATION")
    private fun vibrate(ms: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        } catch (_: Exception) {}
    }

    fun release() {
        _voiceNoteRecording.value = false
        voiceNoteBuffer = null
        stopAudioCapture()
        cleanUpAudioTrack()
        scope.cancel()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
    }
}
