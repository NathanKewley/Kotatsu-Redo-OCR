package org.koitharu.kotatsu.japanese.ui

import org.koitharu.kotatsu.japanese.nlp.JapaneseWord
import org.koitharu.kotatsu.japanese.renshuu.RenshuuKanji
import org.koitharu.kotatsu.japanese.renshuu.RenshuuList
import org.koitharu.kotatsu.japanese.renshuu.RenshuuWord

/** One speech bubble's worth of text, recognised and split into words. */
data class OcrBlockModel(
	val id: Int,
	val text: String,
	val words: List<JapaneseWord>,
)

sealed interface OcrStatus {

	/** No page to read yet, or the sidebar has never been opened. */
	data object Idle : OcrStatus

	data class Working(val phase: Phase) : OcrStatus

	data class Failed(val error: Throwable) : OcrStatus

	data class Done(val blockCount: Int) : OcrStatus

	enum class Phase { LOADING_PAGE, RECOGNIZING, ANALYZING }
}

sealed interface TranslationState {

	data object Disabled : TranslationState

	data class Pending(val isDownloadingModel: Boolean) : TranslationState

	data class Done(val text: String) : TranslationState

	data object Failed : TranslationState
}

sealed interface WordDetails {

	val word: JapaneseWord

	data class Loading(override val word: JapaneseWord) : WordDetails

	/** Renshuu is not set up: the offline reading is all there is to show, plus a way to the setting. */
	data class NoApiKey(override val word: JapaneseWord) : WordDetails

	data class Failed(override val word: JapaneseWord, val error: Throwable) : WordDetails

	data class Loaded(
		override val word: JapaneseWord,
		val entries: List<RenshuuWord>,
		val kanji: KanjiDetails?,
		val addingIds: Set<Long>,
	) : WordDetails
}

sealed interface KanjiDetails {

	val kanji: String

	data class Loading(override val kanji: String) : KanjiDetails

	data class Loaded(override val kanji: String, val details: RenshuuKanji) : KanjiDetails

	data class Failed(override val kanji: String, val error: Throwable) : KanjiDetails
}

/** A row in the sidebar: a bubble plus everything the reader has opened on it. */
data class OcrBlockItem(
	val block: OcrBlockModel,
	val translation: TranslationState,
	val isTranslationRevealed: Boolean,
	val selectedWordIndex: Int,
	val details: WordDetails?,
	val isFuriganaVisible: Boolean,
)

data class OcrSidebarContent(
	val status: OcrStatus,
	val items: List<OcrBlockItem>,
	val isAllRevealed: Boolean,
)

data class ListChoice(
	val word: RenshuuWord,
	val lists: List<RenshuuList>,
	val selectedListKey: String?,
)

sealed interface OcrMessage {

	data class Added(val word: String, val list: String) : OcrMessage

	data class AlreadyInList(val word: String, val list: String) : OcrMessage

	data object NoLists : OcrMessage
}
