package cz.digitalnivedomi.diarium.ui.history

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.digitalnivedomi.diarium.core.data.HistoryData

/**
 * What the Historie screen renders from.
 *
 * [loading] is a first-class flag rather than "data == null": the screen has to tell
 * "still loading" from "loaded, and this month is empty". Collapsing those two is
 * exactly the silent-failure shape the calendar must not have — a blank grid has to
 * mean "nothing written", never "the read is still in flight".
 */
data class HistoryUiState(
    val loading: Boolean = true,
    val data: HistoryData? = null,
    val errorMessage: String? = null,
)

/**
 * Plain state holder — no ViewModel, no Android dependency — so the load/error flow
 * is drivable from a JVM test. Mirrors `DashboardStateHolder`.
 */
class HistoryStateHolder {

    var state: HistoryUiState by mutableStateOf(HistoryUiState())
        private set

    fun markLoading() {
        state = state.copy(loading = true, errorMessage = null)
    }

    fun show(data: HistoryData) {
        state = state.copy(loading = false, data = data, errorMessage = null)
    }

    /**
     * A failed load drops the month instead of keeping the previous one: a stale
     * calendar would look current while showing days that are no longer being shown.
     */
    fun showError(message: String) {
        state = state.copy(loading = false, data = null, errorMessage = message)
    }
}
