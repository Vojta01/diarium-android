package cz.digitalnivedomi.diarium.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.digitalnivedomi.diarium.core.data.DiaryEntry
import cz.digitalnivedomi.diarium.ui.history.DayDetail
import cz.digitalnivedomi.diarium.ui.theme.InkDeep
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** Test tag on the window itself, so a test can assert it opened and closed. */
const val DAY_DETAIL_DIALOG_TAG = "day_detail_dialog"

/**
 * One day's whole record in a window over the current screen.
 *
 * The dashboard's week strip and the statistics tab both need the same thing the
 * history tab already shows — the mood, the sleep and stress labels, the gratitude
 * lines and the AI reflection of a single day — so the surface lives here once
 * instead of twice. [DayDetail] does the rendering; this only adds the frame
 * (a dark panel, a close action, scrolling) and the "tap outside closes" behaviour.
 *
 * It never fetches anything: [entry] is the row the calling screen already loaded
 * (`DashboardData.entryByDate` / `StatsData.entryByDate`), which is why opening a
 * day costs no request and works offline with whatever was last synced. A null
 * [entry] is not an error — [DayDetail] renders its "check-in nevyplněn" card and
 * offers the check-in instead, exactly as the history tab does.
 *
 * [onOpenCheckIn] is called with the ISO date after the window closes itself, so the
 * caller can navigate without the modal staying on top of the form.
 */
@Composable
fun DayDetailDialog(
    date: String,
    entry: DiaryEntry?,
    onDismiss: () -> Unit,
    onOpenCheckIn: (String) -> Unit,
    scaleNames: Map<String, String> = emptyMap(),
    scaleMax: Map<String, Int> = emptyMap(),
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag(DAY_DETAIL_DIALOG_TAG)
                .clip(RoundedCornerShape(24.dp))
                .background(InkDeep.copy(alpha = 0.98f))
                .border(1.dp, Outline, RoundedCornerShape(24.dp))
                .padding(top = 12.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Detail dne",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onDismiss() },
                ) {
                    Text(text = "Zavřít", color = TextSecondary)
                }
            }
            VSpace(10)
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // The card brings its own accent and its own "Detail dne" header, so the
                // frame above stays a plain bar with just the close action.
                DayDetail(
                    date = date,
                    entry = entry,
                    scaleNames = scaleNames,
                    scaleMax = scaleMax,
                    onOpenCheckIn = { requested ->
                        onDismiss()
                        onOpenCheckIn(requested)
                    },
                )
            }
        }
    }
}
