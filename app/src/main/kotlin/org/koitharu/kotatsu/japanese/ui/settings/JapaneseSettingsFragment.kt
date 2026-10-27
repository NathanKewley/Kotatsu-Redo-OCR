package org.koitharu.kotatsu.japanese.ui.settings

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.prefs.ReaderControl
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.japanese.ocr.EngineSetup
import org.koitharu.kotatsu.japanese.ocr.ModelStore
import org.koitharu.kotatsu.japanese.ui.EngineDialogs
import org.koitharu.kotatsu.japanese.ui.settings.JapaneseSettingsViewModel.ApiKeyState
import org.koitharu.kotatsu.japanese.ui.titleRes
import javax.inject.Inject

@AndroidEntryPoint
class JapaneseSettingsFragment : BasePreferenceFragment(R.string.japanese_learning) {

	private val viewModel by viewModels<JapaneseSettingsViewModel>()

	@Inject
	lateinit var engineSetup: EngineSetup

	@Inject
	lateinit var models: ModelStore

	private val engineDialogs by lazy { EngineDialogs(requireActivity() as AppCompatActivity, engineSetup, models) }

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_japanese)
		findPreference<SwitchPreferenceCompat>(AppSettings.KEY_JP_OCR_BUTTON)?.let { pref ->
			pref.isChecked = ReaderControl.JAPANESE_OCR in settings.readerControls
			pref.setOnPreferenceChangeListener { _, newValue ->
				val controls = settings.readerControls.toMutableSet()
				if (newValue == true) controls += ReaderControl.JAPANESE_OCR else controls -= ReaderControl.JAPANESE_OCR
				settings.readerControls = controls
				true
			}
		}
		findPreference<EditTextPreference>(AppSettings.KEY_RENSHUU_API_KEY)?.setOnBindEditTextListener {
			it.setHint(R.string.renshuu_api_key_hint)
			it.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
			it.isSingleLine = true
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		viewModel.apiKeyState.observe(viewLifecycleOwner, ::bindApiKeyState)
		engineSetup.state.observe(viewLifecycleOwner, engineDialogs::bind)
		settings.observeAsFlow(AppSettings.KEY_JP_OCR_ENGINE) { ocrEngine }.observe(viewLifecycleOwner) { engine ->
			findPreference<Preference>(AppSettings.KEY_JP_OCR_ENGINE)?.summary =
				getString(engine?.takeIf { it.isSupported }?.titleRes() ?: R.string.ocr_engine_none)
		}
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean = when (preference.key) {
		AppSettings.KEY_JP_OCR_ENGINE -> {
			engineDialogs.showChooser(settings.ocrEngine)
			true
		}

		KEY_OPEN_RENSHUU -> {
			router.openExternalBrowser(RENSHUU_URL, getString(R.string.renshuu_open_settings))
			true
		}

		else -> super.onPreferenceTreeClick(preference)
	}

	private fun bindApiKeyState(state: ApiKeyState) {
		val pref = findPreference<EditTextPreference>(AppSettings.KEY_RENSHUU_API_KEY) ?: return
		pref.summary = when (state) {
			ApiKeyState.Empty -> getString(R.string.renshuu_api_key_summary)
			ApiKeyState.Checking -> getString(R.string.loading_)
			ApiKeyState.Invalid -> getString(R.string.renshuu_api_key_invalid)
			is ApiKeyState.Valid -> getString(R.string.renshuu_api_key_valid, state.name.ifEmpty { "renshuu" })
			is ApiKeyState.Unchecked -> state.error.getDisplayMessage(resources)
		}
		pref.icon = if (state == ApiKeyState.Invalid) {
			getWarningIcon()
		} else {
			ContextCompat.getDrawable(requireContext(), R.drawable.ic_lock)
		}
	}

	private companion object {

		const val KEY_OPEN_RENSHUU = "renshuu_open"
		const val RENSHUU_URL = "https://www.renshuu.org/"
	}
}
