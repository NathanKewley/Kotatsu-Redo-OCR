package org.koitharu.kotatsu.japanese.ui

import androidx.collection.LruCache
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.japanese.nlp.JapaneseTokenizer
import org.koitharu.kotatsu.japanese.nlp.isKanji
import org.koitharu.kotatsu.japanese.ocr.PageTextRecognizer
import org.koitharu.kotatsu.japanese.renshuu.AddToListResult
import org.koitharu.kotatsu.japanese.renshuu.RenshuuList
import org.koitharu.kotatsu.japanese.renshuu.RenshuuRepository
import org.koitharu.kotatsu.japanese.renshuu.RenshuuWord
import org.koitharu.kotatsu.japanese.translate.JapaneseTranslator
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import javax.inject.Inject

@HiltViewModel
class OcrSidebarViewModel @Inject constructor(
	private val pageLoader: PageLoader,
	private val recognizer: PageTextRecognizer,
	private val tokenizer: JapaneseTokenizer,
	private val translator: JapaneseTranslator,
	private val renshuu: RenshuuRepository,
	private val settings: AppSettings,
) : BaseViewModel() {

	val isOpen = MutableStateFlow(false)

	/** Remembered across chapters and launches: someone who pins it wants it that way every time. */
	val isPinned = MutableStateFlow(settings.isOcrSidebarPinned)
	val onChooseList = MutableEventFlow<ListChoice>()
	val onMessage = MutableEventFlow<OcrMessage>()

	private val page = MutableStateFlow<MangaPage?>(null)
	private val status = MutableStateFlow<OcrStatus>(OcrStatus.Idle)
	private val blocks = MutableStateFlow<List<OcrBlockModel>>(emptyList())
	private val revealed = MutableStateFlow<Set<Int>>(emptySet())
	private val selection = MutableStateFlow<Selection?>(null)
	private val reloadCounter = MutableStateFlow(0)

	/** Keyed by the Japanese text, so a line seen on an earlier page is not translated twice. */
	private val translations = MutableStateFlow<Map<String, TranslationState>>(emptyMap())

	/** Recognised pages, so flipping back a page is instant and costs no battery. */
	private val pageCache = LruCache<Long, List<OcrBlockModel>>(PAGE_CACHE_SIZE)

	private var lookupJob: Job? = null
	private var kanjiJob: Job? = null

	val content: StateFlow<OcrSidebarContent> = combine(
		combine(status, blocks, translations, ::Triple),
		revealed,
		selection,
		settings.observeAsFlow(AppSettings.KEY_JP_FURIGANA) { isJapaneseFuriganaEnabled },
	) { (status, blocks, translations), revealed, selection, isFurigana ->
		OcrSidebarContent(
			status = status,
			items = blocks.map { block ->
				val selected = selection?.takeIf { it.blockId == block.id }
				OcrBlockItem(
					block = block,
					translation = translations[block.text] ?: TranslationState.Disabled,
					isTranslationRevealed = block.id in revealed,
					selectedWordIndex = selected?.wordIndex ?: -1,
					details = selected?.details,
					isFuriganaVisible = isFurigana,
				)
			},
			isAllRevealed = blocks.isNotEmpty() && blocks.all { it.id in revealed },
		)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, EMPTY_CONTENT)

	init {
		launchJob(Dispatchers.Default) {
			// Nothing is recognised while the sidebar is closed: OCR on every page turn would drain the
			// battery of someone who is just reading.
			combine(isOpen, page, reloadCounter) { open, page, reloads -> if (open && page != null) page to reloads else null }
				.distinctUntilChanged()
				.collectLatest { request ->
					if (request != null) {
						processPage(request.first)
					}
				}
		}
	}

	fun setOpen(open: Boolean) {
		isOpen.value = open
	}

	fun togglePinned() {
		val pinned = !isPinned.value
		isPinned.value = pinned
		settings.isOcrSidebarPinned = pinned
	}

	fun onPageChanged(page: MangaPage?) {
		this.page.value = page
	}

	fun reload() {
		page.value?.let { pageCache.remove(it.id) }
		translations.update { map -> map.filterValues { it is TranslationState.Done } }
		reloadCounter.update { it + 1 }
	}

	fun toggleTranslation(blockId: Int) {
		revealed.update { if (blockId in it) it - blockId else it + blockId }
	}

	fun toggleAllTranslations() {
		val all = blocks.value.mapTo(HashSet()) { it.id }
		revealed.update { if (it.containsAll(all)) emptySet() else all }
	}

	fun onWordClick(blockId: Int, wordIndex: Int) {
		val current = selection.value
		lookupJob?.cancel()
		kanjiJob?.cancel()
		if (current != null && current.blockId == blockId && current.wordIndex == wordIndex) {
			selection.value = null // a second tap folds the details away again
			return
		}
		val word = blocks.value.find { it.id == blockId }?.words?.getOrNull(wordIndex) ?: return
		if (!renshuu.isConfigured) {
			selection.value = Selection(blockId, wordIndex, WordDetails.NoApiKey(word))
			return
		}
		selection.value = Selection(blockId, wordIndex, WordDetails.Loading(word))
		lookupJob = launchJob(Dispatchers.Default + SkipErrors) {
			val details = runCatchingCancellable {
				// The dictionary form first; when the analyser's guess finds nothing (a name, slang,
				// an OCR slip), what was actually written on the page.
				val entries = renshuu.searchWords(word.lookup).ifEmpty {
					if (word.surface != word.lookup) renshuu.searchWords(word.surface) else emptyList()
				}
				WordDetails.Loaded(word, entries.take(MAX_ENTRIES), kanji = null, addingIds = emptySet())
			}.getOrElse { error ->
				error.printStackTraceDebug()
				WordDetails.Failed(word, error)
			}
			updateDetails(blockId, wordIndex) { details }
			val best = (details as? WordDetails.Loaded)?.entries?.firstOrNull()
			if (best != null && settings.isRenshuuPresenceEnabled) {
				// Only for the best match, and after the definitions are already showing: it is a
				// second call against the daily allowance and should not hold the answer up.
				val lists = runCatchingCancellable { renshuu.getPresence(best.id) }
					.onFailure { it.printStackTraceDebug() }
					.getOrNull()
				if (!lists.isNullOrEmpty()) {
					lists.forEach { markInList(best.id, it) }
				}
			}
		}
	}

	fun onKanjiClick(kanji: String) {
		val current = selection.value ?: return
		val loaded = current.details as? WordDetails.Loaded ?: return
		kanjiJob?.cancel()
		if (loaded.kanji?.kanji == kanji) {
			updateDetails(current.blockId, current.wordIndex) { (it as WordDetails.Loaded).copy(kanji = null) }
			return
		}
		updateDetails(current.blockId, current.wordIndex) {
			(it as WordDetails.Loaded).copy(kanji = KanjiDetails.Loading(kanji))
		}
		kanjiJob = launchJob(Dispatchers.Default + SkipErrors) {
			val result = runCatchingCancellable {
				KanjiDetails.Loaded(kanji, renshuu.getKanji(kanji))
			}.getOrElse { KanjiDetails.Failed(kanji, it) }
			updateDetails(current.blockId, current.wordIndex) {
				(it as? WordDetails.Loaded)?.takeIf { d -> d.kanji?.kanji == kanji }?.copy(kanji = result) ?: it
			}
		}
	}

	fun retryLookup() {
		val current = selection.value ?: return
		selection.value = null
		onWordClick(current.blockId, current.wordIndex)
	}

	fun onAddToListClick(word: RenshuuWord) {
		launchJob(Dispatchers.Default) {
			val lists = renshuu.getVocabLists()
			if (lists.isEmpty()) {
				onMessage.call(OcrMessage.NoLists)
			} else {
				onChooseList.call(ListChoice(word, lists, settings.renshuuListKey))
			}
		}
	}

	fun addToList(word: RenshuuWord, list: RenshuuList) {
		settings.renshuuListKey = list.key
		setAdding(word.id, true)
		launchJob(Dispatchers.Default) {
			try {
				val result = renshuu.addWordToList(word.id, list)
				onMessage.call(
					when (result) {
						AddToListResult.ADDED -> OcrMessage.Added(word.headword, list.title)
						AddToListResult.ALREADY_PRESENT -> OcrMessage.AlreadyInList(word.headword, list.title)
					},
				)
				markInList(word.id, list)
			} finally {
				setAdding(word.id, false)
			}
		}
	}

	private suspend fun processPage(page: MangaPage) {
		revealed.value = emptySet()
		selection.value = null
		val cached = pageCache[page.id]
		val result = if (cached != null) {
			cached
		} else {
			blocks.value = emptyList()
			try {
				status.value = OcrStatus.Working(OcrStatus.Phase.LOADING_PAGE)
				val uri = pageLoader.loadPage(page, force = false)
				status.value = OcrStatus.Working(OcrStatus.Phase.RECOGNIZING)
				val ocr = recognizer.recognize(uri)
				status.value = OcrStatus.Working(OcrStatus.Phase.ANALYZING)
				ocr.mapIndexed { index, block ->
					OcrBlockModel(id = index, text = block.text, words = tokenizer.tokenize(block.text))
				}.also { pageCache.put(page.id, it) }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				e.printStackTraceDebug()
				status.value = OcrStatus.Failed(e)
				return
			}
		}
		blocks.value = result
		status.value = OcrStatus.Done(result.size)
		translate(result)
	}

	/**
	 * Translates the page's bubbles one at a time, top of the page first, so the bubble the reader
	 * is most likely to reveal is ready soonest. Runs inside the page's job: turning the page stops it.
	 */
	private suspend fun translate(blocks: List<OcrBlockModel>) {
		if (!settings.isJapaneseTranslationEnabled) {
			return
		}
		val todo = blocks.map { it.text }.distinct().filter { translations.value[it] !is TranslationState.Done }
		if (todo.isEmpty()) {
			return
		}
		val isDownloading = runCatchingCancellable { translator.isModelDownloadNeeded() }.getOrDefault(false)
		translations.update { map -> map + todo.associateWith { TranslationState.Pending(isDownloading) } }
		for (text in todo) {
			val state = runCatchingCancellable {
				TranslationState.Done(translator.translate(text))
			}.getOrElse { error ->
				error.printStackTraceDebug()
				TranslationState.Failed
			}
			translations.update { it + (text to state) }
		}
	}

	private fun updateDetails(blockId: Int, wordIndex: Int, transform: (WordDetails) -> WordDetails) {
		selection.update { sel ->
			if (sel != null && sel.blockId == blockId && sel.wordIndex == wordIndex) {
				sel.copy(details = transform(sel.details))
			} else {
				sel
			}
		}
	}

	private fun setAdding(wordId: Long, isAdding: Boolean) {
		val sel = selection.value ?: return
		updateDetails(sel.blockId, sel.wordIndex) { details ->
			if (details is WordDetails.Loaded) {
				details.copy(addingIds = if (isAdding) details.addingIds + wordId else details.addingIds - wordId)
			} else {
				details
			}
		}
	}

	private fun markInList(wordId: Long, list: RenshuuList) {
		val sel = selection.value ?: return
		updateDetails(sel.blockId, sel.wordIndex) { details ->
			if (details is WordDetails.Loaded) {
				details.copy(
					entries = details.entries.map { entry ->
						if (entry.id == wordId && entry.inLists.none { it.key == list.key }) {
							entry.copy(inLists = entry.inLists + list)
						} else {
							entry
						}
					},
				)
			} else {
				details
			}
		}
	}

	private data class Selection(
		val blockId: Int,
		val wordIndex: Int,
		val details: WordDetails,
	)

	companion object {

		private const val PAGE_CACHE_SIZE = 20
		private const val MAX_ENTRIES = 3
		private val EMPTY_CONTENT = OcrSidebarContent(OcrStatus.Idle, emptyList(), isAllRevealed = false)

		/** The kanji in a word, each once, in the order they are written: the "parts" to break it into. */
		fun kanjiOf(text: String): List<String> = text.filter { it.isKanji() && it != '々' }
			.map { it.toString() }
			.distinct()
	}
}
