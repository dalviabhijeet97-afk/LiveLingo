# LiveLingo: Google Live Caption-style upgrade plan

## Current behavior (0.3.x)
- The app uses Android `SpeechRecognizer` and the microphone.
- It translates finalized Japanese recognition results with ML Kit on-device translation.
- It displays Japanese and English in a floating overlay.
- This is **not** yet direct browser/media audio capture, and speech recognition availability/offline behavior depends on the Android speech provider installed on the device.

## Phase 1 — stabilize the current prototype
- Recreate the speech recognizer after failures rather than reusing a potentially stuck instance.
- Apply bounded exponential backoff to avoid tight start/stop loops.
- Translate final speech results rather than every partial hypothesis to reduce stale translation callbacks and caption flicker.
- Verify foreground-service and overlay lifecycle on the target tablet.

## Phase 2 — capture eligible app playback
- Request MediaProjection consent from the user.
- Use `AudioPlaybackCaptureConfiguration` and `AudioRecord` for apps that allow playback capture.
- Handle denied consent, unsupported apps, protected audio, and capture-session termination without crashing.
- Do not promise universal capture: Android apps can opt out, and protected audio cannot be captured this way.

## Phase 3 — on-device transcription
- Feed captured PCM audio into an offline speech-recognition engine that accepts audio buffers (for example, a bundled Whisper-compatible runtime).
- Add model download/storage checks and show clear progress/error states.
- Keep audio processing local unless the user explicitly chooses a cloud provider.
- Benchmark model size, latency, memory, and battery usage on the actual tablet before selecting a model.

## Phase 4 — subtitle experience
- Keep a persistent, draggable, resizable overlay with pause/resume and close controls.
- Update captions in chunks with timestamps, deduplicate repeated hypotheses, and retain the last few lines.
- Provide a fallback microphone mode for apps that cannot be captured.

## Acceptance tests
1. Start once; the service and overlay remain active for a 10-minute session.
2. Pause/resume and stop/close are idempotent and do not leave an orphan overlay or notification.
3. Recognition errors back off and recover without a rapid loop.
4. Partial hypotheses do not flood translation requests.
5. Playback capture works only after explicit consent and only for capture-eligible apps.
6. Captions clearly report whether the current source is playback capture or microphone fallback.
7. Run a full Gradle build and install/test the APK on the target tablet before calling the release complete.
