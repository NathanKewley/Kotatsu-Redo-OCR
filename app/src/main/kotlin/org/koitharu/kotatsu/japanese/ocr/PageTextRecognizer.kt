package org.koitharu.kotatsu.japanese.ocr

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.net.toFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a manga page with the recogniser chosen in settings (see [EngineSetup]), looked up for each
 * page so a change applies from the next one.
 */
@Singleton
class PageTextRecognizer @Inject constructor(
	private val models: ModelStore,
	private val settings: AppSettings,
) {

	private val mlKit by lazy { MlKitTextRecognizer() }
	private val paddle by lazy { PaddleTextRecognizer(models) }
	private val paddleManga by lazy { PaddleTextRecognizer(models, MangaOcrReader(models)) }

	/** Throws [ModelsMissingException] until a recogniser has been chosen and its models are in place. */
	suspend fun recognize(pageUri: Uri): List<OcrBlock> {
		val recognizer = when (settings.ocrEngine?.takeIf { it.isSupported && models.isReady(it) }) {
			OcrEngine.PADDLE_MANGA -> paddleManga
			OcrEngine.PADDLE -> paddle
			OcrEngine.MLKIT -> mlKit
			null -> throw ModelsMissingException()
		}
		val bitmap = decode(pageUri)
		try {
			return recognizer.recognize(bitmap)
		} finally {
			bitmap.recycle()
		}
	}

	private suspend fun decode(uri: Uri): Bitmap = runInterruptible(Dispatchers.IO) {
		decodeImpl(uri).toSoftware()
	}

	private fun decodeImpl(uri: Uri): Bitmap {
		// Same two shapes of page the reader itself handles: a cached file, or an entry inside a local
		// CBZ. BitmapDecoderCompat rather than BitmapFactory so AVIF and JPEG XL pages work too.
		return if (uri.isZipUri()) {
			ZipFile(uri.schemeSpecificPart).use { zip ->
				val entry = zip.getEntry(uri.fragment)
				zip.getInputStream(entry).use {
					BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
				}
			}
		} else {
			BitmapDecoderCompat.decode(uri.toFile())
		}
	}

	/**
	 * Plain ARGB in software memory. ImageDecoder hands back hardware bitmaps on Android 9+, whose
	 * pixels live on the GPU: they can be drawn but not cropped, scaled or read, and the recognisers
	 * do all three. HDR and some PNG pages decode to other configs, which the models' input code and
	 * the ink map do not expect.
	 */
	private fun Bitmap.toSoftware(): Bitmap {
		val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && config == Bitmap.Config.HARDWARE
		if (!isHardware && config == Bitmap.Config.ARGB_8888) {
			return this
		}
		return copy(Bitmap.Config.ARGB_8888, false).also { recycle() }
	}
}
