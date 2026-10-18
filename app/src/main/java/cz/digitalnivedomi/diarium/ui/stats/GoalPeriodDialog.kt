package cz.digitalnivedomi.diarium.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cz.digitalnivedomi.diarium.core.data.Goal
import cz.digitalnivedomi.diarium.core.data.GoalPeriodResult
import cz.digitalnivedomi.diarium.core.data.currentIsoDate
import cz.digitalnivedomi.diarium.core.data.daysBetween
import cz.digitalnivedomi.diarium.core.data.normalizeFrequency
import cz.digitalnivedomi.diarium.core.data.periodLabelFor
import cz.digitalnivedomi.diarium.ui.components.VSpace
import cz.digitalnivedomi.diarium.ui.theme.InkDeep
import cz.digitalnivedomi.diarium.ui.theme.Outline
import cz.digitalnivedomi.diarium.ui.theme.SuccessGreen
import cz.digitalnivedomi.diarium.ui.theme.TextPrimary
import cz.digitalnivedomi.diarium.ui.theme.TextSecondary

/** Test tag on the window itself, so a test can assert it opened and closed. */
const val GOAL_PERIOD_DIALOG_TAG = "goal_period_dialog"

/**
 * One goal period in a window over the statistics screen: which period it is, how many
 * days logged the activity against the goal's target, and — while the period runs — how
 * long is left.
 *
 * It never fetches anything: [period] is one item of the history the statistics screen
 * already loaded, so a tap costs no request and works offline. The frame mirrors
 * `DayDetailDialog` (dark panel, a close action in the header, tap outside closes).
 */
@Composable
internal fun GoalPeriodDialog(
    goal: Goal,
    period: GoalPeriodResult,
    isCurrent: Boolean,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag(GOAL_PERIOD_DIALOG_TAG)
                .clip(RoundedCornerShape(24.dp))
                .background(InkDeep.copy(alpha = 0.98f))
                .border(1.dp, Outline, RoundedCornerShape(24.dp))
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp)
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${goalEmoji(goal.activityKey)} ${goal.name}",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .clickable { onDismiss() },
                ) {
                    Text(text = "Zavřít", color = TextSecondary)
                }
            }

            VSpace(14)
            Text(
                text = periodTitle(goal.frequency, period),
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
            )
            periodRangeText(goal.frequency, period)?.let { range ->
                VSpace(2)
                Text(text = range, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            }

            VSpace(14)
            when (period.met) {
                true -> Text("✔ Splněno", color = SuccessGreen, style = MaterialTheme.typography.titleSmall)
                false -> Text("✘ Nesplněno", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                null -> Text("Bez záznamů", color = TextSecondary, style = MaterialTheme.typography.titleSmall)
            }

            VSpace(10)
            if (period.met != null) {
                Text(
                    text = daysCountText(period.count),
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                VSpace(2)
                Text(
                    text = "cíl ${goal.targetCount}× za ${periodLabelFor(goal.frequency)}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (isCurrent) {
                    VSpace(8)
                    Text(
                        text = currentHint(period),
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            } else {
                Text(
                    text = if (isCurrent) "Pro toto období zatím nejsou žádné záznamy."
                    else "Pro toto období nejsou žádné záznamy.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (period.dates.isNotEmpty()) {
                VSpace(14)
                Text("Dny s aktivitou", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                VSpace(4)
                Text(
                    text = daysListText(period.dates),
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** „17 dní s aktivitou" with the Czech plural of „den" (1 den, 3 dny, 17 dní). */
private fun daysCountText(count: Int): String = when (count) {
    1 -> "1 den s aktivitou"
    in 2..4 -> "$count dny s aktivitou"
    else -> "$count dní s aktivitou"
}

/**
 * „Probíhá — zbývají 3 dny." for a running period, counted to its real calendar end
 * ([GoalPeriodResult.end]); the last day says so instead of a bare „zbývá 0".
 */
private fun currentHint(period: GoalPeriodResult): String {
    val remaining = daysBetween(currentIsoDate(), period.end) ?: 0L
    return when {
        remaining <= 0L -> "Probíhá — poslední den období."
        remaining == 1L -> "Probíhá — zbývá 1 den."
        remaining in 2L..4L -> "Probíhá — zbývají $remaining dny."
        else -> "Probíhá — zbývá $remaining dní."
    }
}

/** The period's headline: „Srpen 2026", „Týden 14.–20. 9. 2026" or „14. 10. 2026". */
private fun periodTitle(frequency: String, period: GoalPeriodResult): String =
    when (normalizeFrequency(frequency)) {
        "monthly" -> "${MONTHS_LONG[isoMonth(period.start)]} ${isoYear(period.start)}"
        "daily" -> "${isoDay(period.start)}. ${isoMonth(period.start) + 1}. ${isoYear(period.start)}"
        else -> {
            val sameMonth = isoMonth(period.start) == isoMonth(period.end)
            val head = if (sameMonth) {
                "${isoDay(period.start)}.–${isoDay(period.end)}. ${isoMonth(period.end) + 1}. ${isoYear(period.end)}"
            } else {
                "${isoDay(period.start)}. ${isoMonth(period.start) + 1}. – ${isoDay(period.end)}. " +
                    "${isoMonth(period.end) + 1}. ${isoYear(period.end)}"
            }
            "Týden $head"
        }
    }

/**
 * The whole date range under the title. Only a monthly period needs it — its title is
 * just the month name — while a week or a day already carries its dates in the title.
 */
private fun periodRangeText(frequency: String, period: GoalPeriodResult): String? =
    if (normalizeFrequency(frequency) == "monthly") {
        "${isoDay(period.start)}.–${isoDay(period.end)}. ${isoMonth(period.end) + 1}. ${isoYear(period.end)}"
    } else {
        null
    }

/**
 * „13., 14., 15. 10." — the month named once — or „28. 9., 1. 10." when the days span
 * two months (a weekly window can).
 */
private fun daysListText(dates: List<String>): String {
    val oneMonth = dates.all { isoMonth(it) == isoMonth(dates.first()) }
    return if (oneMonth) {
        dates.joinToString(", ") { "${isoDay(it)}." } + " ${isoMonth(dates.first()) + 1}."
    } else {
        dates.joinToString(", ") { "${isoDay(it)}. ${isoMonth(it) + 1}." }
    }
}

/** 0-based month from an ISO date; a malformed date degrades to January, never a crash. */
private fun isoMonth(iso: String): Int = (iso.drop(5).take(2).toIntOrNull() ?: 1) - 1

/** The day-of-month from an ISO date, without the leading zero ("05" → "5"). */
private fun isoDay(iso: String): String = iso.drop(8).take(2).trimStart('0').ifBlank { "0" }

/** The year from an ISO date. */
private fun isoYear(iso: String): String = iso.take(4)

private val MONTHS_LONG = listOf(
    "Leden", "Únor", "Březen", "Duben", "Květen", "Červen",
    "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec",
)
