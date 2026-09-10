package cz.digitalnivedomi.diarium.core.data

/** An activity the user can tick; `source` is `catalog`, `user` or `default`. */
data class ActivityDef(
    val key: String,
    val label: String,
    val icon: String = "",
    val category: String = "obecné",
    val color: String = "#6366F1",
    val source: String = "catalog",
    val isActive: Boolean = true,
)

/** A habit toggle; `isNegative` flips the meaning (green = "I did NOT do it"). */
data class HabitDef(
    val key: String,
    val label: String,
    val icon: String = "",
    val category: String = "obecné",
    val color: String = "#6366F1",
    val isNegative: Boolean = false,
    val source: String = "catalog",
    val isActive: Boolean = true,
)

/** A numeric scale shown as a row of buttons under the note field. */
data class Scale(
    val id: String,
    val name: String,
    val emoji: String = "📊",
    val minValue: Int = 1,
    val maxValue: Int = 5,
    val color: String = "#6366F1",
    val sortOrder: Int = 0,
)

/** A note template — selecting one REPLACES the note (never appends). */
data class NoteTemplate(
    val id: String,
    val name: String,
    val content: String,
    val sortOrder: Int = 0,
)
