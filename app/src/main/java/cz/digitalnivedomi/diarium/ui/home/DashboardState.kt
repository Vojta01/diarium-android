package cz.digitalnivedomi.diarium.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cz.digitalnivedomi.diarium.core.data.DashboardData

/**
 * What the dashboard renders from.
 *
 * [loading] is a first-class flag rather than "data == null": the screen has to tell
 * "still loading" from "loaded, and there is nothing to show yet". Collapsing those
 * two is exactly the silent-failure shape this screen exists to avoid.
 */
data class DashboardUiState(
    val loading: Boolean = true,
    val data: DashboardData? = null,
    val errorMessage: String? = null,
)

/**
 * Plain state holder — no ViewModel, no Android dependency — so the load/error flow
 * is drivable from a JVM test.
 */
class DashboardStateHolder {

    var state: DashboardUiState by mutableStateOf(DashboardUiState())
        private set

    fun markLoading() {
        state = state.copy(loading = true, errorMessage = null)
    }

    fun show(data: DashboardData) {
        state = state.copy(loading = false, data = data, errorMessage = null)
    }

    /**
     * A failed load drops the numbers instead of keeping the previous ones: a stale
     * dashboard would look current while showing days that are no longer being shown.
     */
    fun showError(message: String) {
        state = state.copy(loading = false, data = null, errorMessage = message)
    }
}
