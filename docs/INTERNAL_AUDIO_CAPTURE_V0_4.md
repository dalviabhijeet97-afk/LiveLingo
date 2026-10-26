# LiveLingo 0.4.0 — Internal playback capture prototype

This branch adds an experimental Android internal-playback capture path using MediaProjection consent and AudioPlaybackCaptureConfiguration.

## What this build does
- Requests Android's system screen/audio capture consent.
- Starts a mediaProjection foreground service.
- Reads PCM audio from supported app playback and reports whether non-silent PCM is detected.
- Keeps the existing microphone-based translation path available separately.

## Important limitation
This is a capture-validation milestone, **not yet a caption-capable replacement**. The new capture service does not yet feed PCM into a speech-recognition engine or display translated captions. The next phase is to integrate an audio-buffer-compatible Japanese ASR engine (for example, sherpa-onnx/Whisper-compatible runtime), then connect final Japanese segments to the existing ML Kit Japanese-to-English translator and overlay.

Android's playback-capture API is available from Android 10 (API 29). The source app must allow playback capture; DRM/protected apps or apps that opt out may provide silence. Android also requires RECORD_AUDIO permission as a prerequisite even though this path does not read from the physical microphone.

## Validation
- CI build required for this branch.
- Device test: approve system capture prompt, play audio in a supported app, and check the ongoing notification for "Internal audio detected".
- Verify capture stops cleanly using Stop All.
