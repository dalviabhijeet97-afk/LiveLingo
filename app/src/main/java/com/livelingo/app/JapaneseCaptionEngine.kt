package com.livelingo.app

import android.content.Context
import android.util.Log
import com.google.mlkit.nl.translate.DownloadConditions
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
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip"
        private const val MODEL_DIR_NAME = "vosk-model-small-ja-0.22"
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var translator: Translator? = null
    @Volatile private var closed = false

    /** Call on a worker thread; first launch downloads the Japanese speech model. */
    fun initialize() {
        onStatus("Preparing Japanese speech recognition (first run may download a model)…")
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (!File(modelDir, "am").exists() && !File(modelDir, "conf").exists()) {
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
            if (r.acceptWaveForm(bytes, bytes.size)) {
                val text = JSONObject(r.result).optString("text").trim()
                if (text.isNotBlank()) translate(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR processing failed", e)
        }
    }

    private fun translate(japanese: String) {
        val t = translator ?: return
        t.translate(japanese)
            .addOnSuccessListener { english ->
                if (!closed && english.isNotBlank()) onCaption(english)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Translation failed", e)
                onStatus("Recognized Japanese, but translation failed. Check internet/model download.")
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
            connection.inputStream.use { input ->
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
            }
        } finally {
            connection.disconnect()
        }

        val extractRoot = File(context.filesDir, "vosk-model-extract")
        extractRoot.deleteRecursively()
        extractRoot.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(extractRoot, entry.name).canonicalFile
                if (!out.path.startsWith(extractRoot.canonicalPath + File.separator)) {
                    throw IllegalStateException("Unsafe model archive entry")
                }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val modelRoot = extractRoot.walkTopDown().firstOrNull {
            it.isDirectory && it.name == MODEL_DIR_NAME && File(it, "conf").exists()
        } ?: throw IllegalStateException("Japanese model archive did not contain expected files")
        destination.deleteRecursively()
        if (!modelRoot.copyRecursively(destination, overwrite = true)) {
            throw IllegalStateException("Could not install Japanese speech model")
        }
        extractRoot.deleteRecursively()
        zipFile.delete()
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
