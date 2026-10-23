package org.koitharu.kotatsu.japanese.ui.settings

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.japanese.renshuu.RenshuuAuthException
import org.koitharu.kotatsu.japanese.renshuu.RenshuuRepository
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

@HiltViewModel
class JapaneseSettingsViewModel @Inject constructor(
	private val settings: AppSettings,
	private val repository: RenshuuRepository,
) : BaseViewModel() {

	val apiKeyState: StateFlow<ApiKeyState> = settings.observe(AppSettings.KEY_RENSHUU_API_KEY)
		.onStart { emit(null) }
		.map { settings.renshuuApiKey }
		.transformLatest { key ->
			if (key == null) {
				emit(ApiKeyState.Empty)
				return@transformLatest
			}
			emit(ApiKeyState.Checking)
			// A new key may belong to a different account: answers cached for the old one are not
			// this user's lists.
			repository.clearCache()
			emit(
				runCatchingCancellable {
					ApiKeyState.Valid(repository.checkApiKey(key))
				}.getOrElse { error ->
					if (error is RenshuuAuthException) ApiKeyState.Invalid else ApiKeyState.Unchecked(error)
				},
			)
		}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, ApiKeyState.Checking)

	sealed interface ApiKeyState {

		data object Empty : ApiKeyState

		data object Checking : ApiKeyState

		data class Valid(val name: String) : ApiKeyState

		data object Invalid : ApiKeyState

		/** Could not ask renshuu - offline, most likely. Says nothing about the key itself. */
		data class Unchecked(val error: Throwable) : ApiKeyState
	}
}
