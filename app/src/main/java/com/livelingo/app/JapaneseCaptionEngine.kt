package com.livelingo.app

import android.content.Context
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import android.os.SystemClock

/**
 * Offline Japanese ASR (Vosk) with on-device Japanese -> English translation (ML Kit).
 * The acoustic model is downloaded once on first use and cached in app-private storage.
 */
class JapaneseCaptionEngine(
    private val context: Context,
    private val onCaption: (String) -> Unit,
    private val onStatus: (String) -> Unit
) : AutoCloseable {
    companion object {
        private const val TAG = "LiveLingoASR"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-ja-0.22.zip"
        private const val MODEL_DIR_NAME = "vosk-model-ja-0.22"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var translator: Translator? = null
    @Volatile private var closed = false
    @Volatile private var translationReady = false
    @Volatile private var lastPartialText = ""
    @Volatile private var lastPartialAt = 0L
    @Volatile private var latestFinalText = ""
    @Volatile private var translationInFlight = false
    @Volatile private var queuedText: String? = null
    @Volatile private var lastFinalAt = 0L

    /** Call on a worker thread; first launch downloads the Japanese speech model. */
    fun initialize() {
        onStatus("Preparing Japanese speech recognition (first run may download a model)…")
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (!File(modelDir, "am").exists() || !File(modelDir, "conf").exists()) {
            downloadAndExtractModel(modelDir)
        }
        model = Model(modelDir.absolutePath)
        recognizer = Recognizer(model, 16000.0f)
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(com.google.mlkit.nl.translate.TranslateLanguage.JAPANESE)
                .setTargetLanguage(com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH)
                .build()
        )
        onStatus("Japanese speech engine ready. Listening to internal audio…")
        translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())
            ?.addOnSuccessListener {
                translationReady = true
                onStatus("Japanese speech and English translation ready.")
                queuedText?.let { pending -> translate(pending, true) }
            }
            ?.addOnFailureListener { e ->
                Log.e(TAG, "Could not download translation model", e)
                onStatus("Speech recognition is ready; English translation model download failed. Check internet and restart.")
            }
    }

    /** Feed PCM16 little-endian audio captured from Android playback. */
    fun process(samples: ShortArray, count: Int) {
        if (closed || count <= 0) return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = samples[i].toInt()
            bytes[i * 2] = (value and 0xff).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xff).toByte()
        }
        val r = recognizer ?: return
        try {
            // Translate only finalized utterances for better accuracy. Partial hypotheses
            // were faster but changed frequently and produced unstable English captions.
            if (r.acceptWaveForm(bytes, bytes.size)) {
                val text = JSONObject(r.result).optString("text").trim()
                if (text.isNotBlank()) {
                    latestFinalText = text
                    lastPartialText = ""
                    lastFinalAt = SystemClock.elapsedRealtime()
                    translate(text, true)
                }
            } else {
                val partial = JSONObject(r.partialResult).optString("partial").trim()
                val now = SystemClock.elapsedRealtime()
                val mode = context.getSharedPreferences("livelingo_settings", Context.MODE_PRIVATE)
                    .getString("caption_mode", "Balanced") ?: "Balanced"
                val partialIntervalMs = when (mode) {
                    "Fast" -> 1400L
                    "Accurate" -> Long.MAX_VALUE // wait for Vosk to finalize the utterance
                    else -> 2600L
                }
                if (partialIntervalMs != Long.MAX_VALUE && partial.length >= 4 &&
                    partial != lastPartialText && now - lastFinalAt >= 1100L &&
                    now - lastPartialAt >= partialIntervalMs) {
                    lastPartialText = partial
                    lastPartialAt = now
                    translate(partial, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR processing failed", e)
        }
    }

    private fun translate(japanese: String, isFinal: Boolean) {
        if (closed || japanese.isBlank()) return
        if (!translationReady) {
            queuedText = japanese
            return
        }
        synchronized(this) {
            if (translationInFlight) {
                // Keep only the newest hypothesis instead of building a queue.
                queuedText = japanese
                return
            }
            translationInFlight = true
            queuedText = null
        }
        val t = translator
        if (t == null) {
            synchronized(this) { translationInFlight = false }
            return
        }
        t.translate(japanese)
            .addOnSuccessListener { english ->
                if (!closed && english.isNotBlank() && (isFinal || japanese == lastPartialText)) onCaption(english)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation failed", e)
                onStatus("Recognized Japanese, but translation failed. Check internet/model download.")
            }
            .addOnCompleteListener {
                val next: String?
                synchronized(this) {
                    translationInFlight = false
                    next = queuedText
                    queuedText = null
                }
                if (!closed && next != null && next != japanese) {
                    translate(next, next == latestFinalText)
                }
            }
    }

    private fun downloadAndExtractModel(destination: File) {
        val zipFile = File(context.cacheDir, "vosk-ja-model.zip")
        onStatus("Downloading Japanese speech model (first run only)…")
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model download returned HTTP ${connection.responseCode}")
            }
            val totalBytes = connection.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (downloadedBytes - lastReportedBytes >= 1024 * 1024 ||
                            (totalBytes > 0 && downloadedBytes == totalBytes)) {
                            lastReportedBytes = downloadedBytes
                            val downloadedMb = downloadedBytes / (1024.0 * 1024.0)
                            val totalLabel = if (totalBytes > 0) {
                                String.format(java.util.Locale.US, "%.1f MB / %.1f MB", downloadedMb, totalBytes / (1024.0 * 1024.0))
                            } else {
                                String.format(java.util.Locale.US, "%.1f MB downloaded (server size unknown)", downloadedMb)
                            }
                            val percent = if (totalBytes > 0) " (${(downloadedBytes * 100 / totalBytes).coerceIn(0, 100)}%)" else ""
                            onStatus("Downloading Vosk Japanese model: $totalLabel$percent")
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }

        // The percentage can reach 100% before the final read completes due to rounding.
        // Switch phases immediately after download, so extraction never looks like a frozen download.
        onStatus("Download finished (${String.format(java.util.Locale.US, "%.1f MB", zipFile.length() / (1024.0 * 1024.0))}). Extracting Vosk Japanese model…")
        val extractRoot = File(context.filesDir, "vosk-model-extract")
        extractRoot.deleteRecursively()
        extractRoot.mkdirs()
        var extractedBytes = 0L
        var entriesProcessed = 0
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(extractRoot, entry.name).canonicalFile
                if (!out.path.startsWith(extractRoot.canonicalPath + File.separator)) {
                    throw IllegalStateException("Unsafe model archive entry")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            extractedBytes += read
                            if (extractedBytes % (32L * 1024 * 1024) < read) {
                                onStatus("Extracting Japanese model: ${String.format(java.util.Locale.US, "%.0f MB", extractedBytes / (1024.0 * 1024.0))} unpacked…")
                            }
                        }
                    }
                }
                entriesProcessed++
                if (entriesProcessed % 100 == 0) {
                    onStatus("Extracting Japanese model: $entriesProcessed files processed, ${String.format(java.util.Locale.US, "%.0f MB", extractedBytes / (1024.0 * 1024.0))} unpacked…")
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        onStatus("Extraction complete. Validating Japanese speech model…")
        val modelRoot = extractRoot.walkTopDown().firstOrNull {
            it.isDirectory && it.name == MODEL_DIR_NAME && File(it, "conf").exists()
        } ?: throw IllegalStateException("Japanese model archive did not contain expected files")
        destination.deleteRecursively()
        // Rename when possible to avoid copying the extracted model a second time.
        if (!modelRoot.renameTo(destination)) {
            modelRoot.copyRecursively(destination, overwrite = true)
        }
        extractRoot.deleteRecursively()
        zipFile.delete()
        onStatus("Japanese model extracted successfully.")
    }

    override fun close() {
        closed = true
        try { recognizer?.close() } catch (_: Exception) {}
        try { model?.close() } catch (_: Exception) {}
        try { translator?.close() } catch (_: Exception) {}
        recognizer = null
        model = null
        translator = null
    }
}
