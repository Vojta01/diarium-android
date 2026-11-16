package cz.digitalnivedomi.diarium.ui.components

/**
 * Maps a horizontal chart coordinate to its nearest time-axis slot. Blank slots remain
 * selectable because they still represent a real day; nullable chart values are not used
 * to manufacture a value for them. Coordinates beyond either edge clamp to edge slots.
 */
internal fun moodTrendIndexAtX(x: Float, width: Float, slotCount: Int): Int? {
    if (slotCount <= 0 || width <= 0f || !width.isFinite() || !x.isFinite()) return null
    val position = (x / width).coerceIn(0f, 1f)
    return (position * slotCount).toInt().coerceAtMost(slotCount - 1)
}
