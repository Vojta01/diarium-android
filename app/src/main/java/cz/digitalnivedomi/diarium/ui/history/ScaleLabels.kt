package cz.digitalnivedomi.diarium.ui.history

import cz.digitalnivedomi.diarium.core.data.Scale
import cz.digitalnivedomi.diarium.ui.checkin.components.labelWithIcon

/**
 * Turning the keys stored on an entry's `scale_values` into names a reader
 * recognises.
 *
 * `scale_values` is a JSON object keyed by the scale row's **uuid** (for example
 * `{"3be23c69-48fe-45c7-b9bf-559552b651b7": 3}`), so the history day detail — which
 * only has the saved entry — would otherwise print that uuid as the scale's name.
 * These helpers join the entry's keys back to the user's `scales` rows (read by
 * [cz.digitalnivedomi.diarium.core.data.PickersRepository.scales]).
 *
 * They are pure functions rather than inline composable logic so the mapping is
 * drivable from a JVM test without Compose.
 */

/** Scale id -> its display label, e.g. `3be23c69-… -> "⚡ Energie"`. */
internal fun scaleNameMap(scales: List<Scale>): Map<String, String> =
    scales.associate { it.id to labelWithIcon(it.emoji, it.name) }

/** Scale id -> its maximum, so a row can read "3 / 5" from the scale itself. */
internal fun scaleMaxMap(scales: List<Scale>): Map<String, Int> =
    scales.associate { it.id to it.maxValue }

/**
 * The value cell of one history scale row: `value / max` when the scale behind the
 * stored key is known, and `/ 5` — the constant the screen used before it had the
 * scale rows — when it is not. An entry written by an old build, or one whose scale
 * has since been deleted, still shows its number rather than nothing.
 */
internal fun scaleValueText(value: Int, scaleId: String, maxByScale: Map<String, Int>): String =
    "$value / ${maxByScale[scaleId] ?: DEFAULT_SCALE_MAX}"

/** Denominator used when the scale behind a stored key cannot be resolved. */
internal const val DEFAULT_SCALE_MAX = 5
