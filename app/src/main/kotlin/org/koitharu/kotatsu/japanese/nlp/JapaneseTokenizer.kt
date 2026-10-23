package org.koitharu.kotatsu.japanese.nlp

import com.atilika.kuromoji.ipadic.Token
import com.atilika.kuromoji.ipadic.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Splits a Japanese sentence into the words a learner would look up.
 *
 * Renshuu's dictionary search wants a word, not a sentence, and Japanese does not put spaces
 * between words - so this is what turns a speech bubble into something tappable.
 */
@Singleton
class JapaneseTokenizer @Inject constructor() {

	private val mutex = Mutex()

	@Volatile
	private var tokenizer: Tokenizer? = null

	suspend fun tokenize(text: String): List<JapaneseWord> = withContext(Dispatchers.Default) {
		WordGrouper.group(getTokenizer().tokenize(text).map { it.toMorpheme() })
	}

	/**
	 * Built on first use, not at injection: the dictionary takes a second or two and tens of
	 * megabytes to load, and most people opening the reader never open the sidebar.
	 */
	private suspend fun getTokenizer(): Tokenizer = tokenizer ?: mutex.withLock {
		tokenizer ?: Tokenizer().also { tokenizer = it }
	}

	private fun Token.toMorpheme() = Morpheme(
		surface = surface,
		baseForm = baseForm.takeUnless { it == UNKNOWN },
		reading = reading.takeUnless { it == UNKNOWN },
		pos = partOfSpeechLevel1,
		pos2 = partOfSpeechLevel2,
	)

	private companion object {

		const val UNKNOWN = "*"
	}
}
