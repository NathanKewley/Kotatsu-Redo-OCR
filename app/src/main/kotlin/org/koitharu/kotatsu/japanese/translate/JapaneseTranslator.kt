package org.koitharu.kotatsu.japanese.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Japanese to English, on the device.
 *
 * The translation is a hint the learner reveals to check themselves, not the thing they are reading,
 * so a free offline model that needs no account wins over a better one behind an API key.
 */
@Singleton
class JapaneseTranslator @Inject constructor() {

	private val translator by lazy {
		Translation.getClient(
			TranslatorOptions.Builder()
				.setSourceLanguage(TranslateLanguage.JAPANESE)
				.setTargetLanguage(TranslateLanguage.ENGLISH)
				.build(),
		)
	}

	private val downloadMutex = Mutex()

	@Volatile
	private var isModelReady = false

	/**
	 * Whether the ~30 MB language model is still to be fetched, so the sidebar can say why the first
	 * translation is slow rather than look stuck.
	 */
	suspend fun isModelDownloadNeeded(): Boolean {
		if (isModelReady) {
			return false
		}
		val model = TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build()
		return !RemoteModelManager.getInstance().isModelDownloaded(model).await()
	}

	suspend fun translate(text: String): String {
		ensureModel()
		return translator.translate(text).await()
	}

	private suspend fun ensureModel() {
		if (isModelReady) {
			return
		}
		// One download, however many bubbles ask for a translation while it is running.
		downloadMutex.withLock {
			if (!isModelReady) {
				translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
				isModelReady = true
			}
		}
	}
}
