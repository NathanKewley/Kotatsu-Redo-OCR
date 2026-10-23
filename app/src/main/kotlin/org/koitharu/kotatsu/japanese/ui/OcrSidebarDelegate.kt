package org.koitharu.kotatsu.japanese.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.SimpleItemAnimator
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.hasGlobalPoint
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.setContentDescriptionAndTooltip
import org.koitharu.kotatsu.core.util.ext.setTooltipCompat
import org.koitharu.kotatsu.databinding.LayoutOcrSidebarBinding
import org.koitharu.kotatsu.japanese.renshuu.RenshuuAuthException
import org.koitharu.kotatsu.japanese.renshuu.RenshuuWord
import org.koitharu.kotatsu.parsers.model.MangaPage

/**
 * The one place the reader touches the Japanese OCR feature, so ReaderActivity - upstream Kotatsu's
 * file, which the fork keeps rebasing onto - only has to create this, forward the toolbar button
 * and page changes, and keep its touch handling off the sidebar.
 */
class OcrSidebarDelegate(
	private val activity: AppCompatActivity,
	private val binding: LayoutOcrSidebarBinding,
	private val snackbarHost: View,
	private val topBar: View,
	private val bottomBar: View?,
	private val viewModel: OcrSidebarViewModel,
	private val currentPage: () -> MangaPage?,
) : OcrSidebarListener {

	private val adapter = OcrBlockAdapter(this)
	private var shownBlocks: List<OcrBlockModel> = emptyList()
	private var systemBars: Insets = Insets.NONE

	private val backCallback = object : OnBackPressedCallback(false) {
		override fun handleOnBackPressed() = viewModel.setOpen(false)
	}

	init {
		binding.recyclerView.adapter = adapter
		// A tapped word swaps the card's contents in place; the default cross-fade makes it blink.
		(binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
		binding.buttonClose.setOnClickListener { viewModel.setOpen(false) }
		binding.buttonReload.setOnClickListener { viewModel.reload() }
		binding.buttonRetry.setOnClickListener { viewModel.reload() }
		binding.buttonRevealAll.setOnClickListener { viewModel.toggleAllTranslations() }
		binding.buttonClose.setTooltipCompat(binding.buttonClose.contentDescription)
		binding.buttonReload.setTooltipCompat(binding.buttonReload.contentDescription)
		activity.onBackPressedDispatcher.addCallback(activity, backCallback)
		// The bars' own layout, not the reader's insets: when the reader UI appears, the insets are
		// worked out before the app bar has been laid out at its new size.
		val onBarLayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePadding() }
		topBar.addOnLayoutChangeListener(onBarLayout)
		bottomBar?.addOnLayoutChangeListener(onBarLayout)
		viewModel.isOpen.observe(activity, ::onOpenChanged)
		viewModel.content.observe(activity, ::onContentChanged)
		viewModel.onChooseList.observeEvent(activity, ::showListChooser)
		viewModel.onMessage.observeEvent(activity, ::showMessage)
		viewModel.onError.observeEvent(activity, ::showError)
	}

	fun toggle() {
		val open = !viewModel.isOpen.value
		if (open) {
			viewModel.onPageChanged(currentPage())
		}
		viewModel.setOpen(open)
	}

	/** Call on every page turn. Cheap while closed: the view model only reads a page when open. */
	fun onPageChanged() {
		viewModel.onPageChanged(currentPage())
	}

	/** Keeps the sidebar clear of the system bars, and of the reader's own bars while they show. */
	fun applyInsets(systemBars: Insets) {
		this.systemBars = systemBars
		updatePadding()
	}

	private fun updatePadding() {
		val top = if (topBar.isVisible) maxOf(topBar.bottom, systemBars.top) else systemBars.top
		val bottom = bottomBar?.takeIf { it.isVisible }?.let { maxOf(it.height, systemBars.bottom) } ?: systemBars.bottom
		binding.root.updatePadding(top = top, right = systemBars.right, bottom = bottom)
	}

	/** True for a touch on the sidebar, which the reader's tap grid must not treat as a page tap. */
	fun isTouchInside(rawX: Int, rawY: Int): Boolean = binding.root.isVisible && binding.root.hasGlobalPoint(rawX, rawY)

	override fun onWordClick(blockId: Int, wordIndex: Int) = viewModel.onWordClick(blockId, wordIndex)

	override fun onTranslationClick(blockId: Int) = viewModel.toggleTranslation(blockId)

	override fun onKanjiClick(kanji: String) = viewModel.onKanjiClick(kanji)

	override fun onAddToListClick(word: RenshuuWord) = viewModel.onAddToListClick(word)

	override fun onRetryLookup() = viewModel.retryLookup()

	override fun onOpenSettings() {
		activity.router.openJapaneseSettings()
	}

	private fun onOpenChanged(isOpen: Boolean) {
		backCallback.isEnabled = isOpen
		val sidebar = binding.root
		if (sidebar.isVisible == isOpen) {
			return
		}
		if (activity.isAnimationsEnabled) {
			TransitionManager.beginDelayedTransition(sidebar.parent as ViewGroup, Slide(Gravity.END).addTarget(sidebar))
		}
		sidebar.isVisible = isOpen
	}

	private fun onContentChanged(content: OcrSidebarContent) {
		val blocks = content.items.map { it.block }
		val isNewPage = blocks != shownBlocks
		shownBlocks = blocks
		adapter.submitList(content.items) {
			if (isNewPage) {
				binding.recyclerView.scrollToPosition(0)
			}
		}
		val res = activity.resources
		val status = content.status
		binding.textViewStatus.text = when (status) {
			is OcrStatus.Done -> res.getQuantityString(R.plurals.ocr_blocks, status.blockCount, status.blockCount)
			is OcrStatus.Working -> res.getString(status.phase.titleRes())
			else -> null
		}
		binding.textViewStatus.isVisible = !binding.textViewStatus.text.isNullOrEmpty()
		binding.layoutStatus.isVisible = content.items.isEmpty()
		binding.progressBar.isVisible = status is OcrStatus.Working
		binding.buttonRetry.isVisible = status is OcrStatus.Failed
		binding.textViewMessage.text = when (status) {
			OcrStatus.Idle -> res.getString(R.string.ocr_no_page)
			is OcrStatus.Working -> res.getString(status.phase.titleRes())
			is OcrStatus.Failed -> status.error.getDisplayMessage(res)
			is OcrStatus.Done -> res.getString(R.string.ocr_no_text)
		}
		val hasTranslations = content.items.any { it.translation != TranslationState.Disabled }
		binding.buttonRevealAll.isVisible = hasTranslations
		binding.buttonRevealAll.setIconResource(if (content.isAllRevealed) R.drawable.ic_eye_off else R.drawable.ic_eye)
		binding.buttonRevealAll.setContentDescriptionAndTooltip(
			if (content.isAllRevealed) R.string.ocr_hide_all else R.string.ocr_reveal_all,
		)
	}

	private fun showListChooser(choice: ListChoice) {
		// A tick on the lists that already have the word, so adding it twice is a choice and not
		// an accident.
		val present = choice.word.inLists.mapTo(HashSet()) { it.key }
		val titles = choice.lists.map { if (it.key in present) "✓ ${it.title}" else it.title }
		MaterialAlertDialogBuilder(activity)
			.setTitle(activity.getString(R.string.renshuu_choose_list, choice.word.headword))
			.setSingleChoiceItems(
				titles.toTypedArray(),
				choice.lists.indexOfFirst { it.key == choice.selectedListKey },
			) { dialog, which ->
				dialog.dismiss()
				viewModel.addToList(choice.word, choice.lists[which])
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun showMessage(message: OcrMessage) {
		val text = when (message) {
			is OcrMessage.Added -> activity.getString(R.string.renshuu_added, message.word, message.list)
			is OcrMessage.AlreadyInList -> activity.getString(R.string.renshuu_already_in_list, message.word, message.list)
			OcrMessage.NoLists -> activity.getString(R.string.renshuu_no_lists)
		}
		Snackbar.make(snackbarHost, text, Snackbar.LENGTH_SHORT).show()
	}

	private fun showError(error: Throwable) {
		val snackbar = Snackbar.make(snackbarHost, error.getDisplayMessage(activity.resources), Snackbar.LENGTH_LONG)
		if (error is RenshuuAuthException) {
			snackbar.setText(R.string.renshuu_api_key_rejected)
			snackbar.setAction(R.string.settings) { onOpenSettings() }
		}
		snackbar.show()
	}

	private fun OcrStatus.Phase.titleRes(): Int = when (this) {
		OcrStatus.Phase.LOADING_PAGE -> R.string.ocr_loading_page
		OcrStatus.Phase.RECOGNIZING -> R.string.ocr_recognizing
		OcrStatus.Phase.ANALYZING -> R.string.ocr_analyzing
	}
}
