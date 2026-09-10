package cz.digitalnivedomi.diarium.core.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tolerant JSON readers shared by the data layer.
 *
 * PostgREST rows are plain JSON and columns may legitimately be absent (older
 * rows predate a migration), so every reader treats "missing" and "blank" as
 * the same thing instead of throwing.
 */

/**
 * JSON null arrives from `optString` as the literal string "null" (the Android
 * `org.json` behaviour), so a SQL NULL column — which PostgREST returns as JSON
 * null — would otherwise reach the UI and render as the word "null". This maps
 * that case — and a missing key, and a genuinely blank value — back to absent.
 */
internal fun JSONObject?.stringOrNull(key: String): String? {
    if (this == null || isNull(key)) return null
    return optString(key).takeIf { it.isNotBlank() && it != "null" }
}

/** [stringOrNull], but "absent" is the empty string — drop-in for `optString`. */
internal fun JSONObject?.plainString(key: String): String = stringOrNull(key).orEmpty()

/** String array → non-blank strings, skipping JSON-null/empty/"null" slots. */
internal fun JSONArray?.stringList(): List<String> {
    if (this == null) return emptyList()
    val out = ArrayList<String>(length())
    for (i in 0 until length()) {
        if (isNull(i)) continue
        val value = optString(i)
        if (value.isNotBlank() && value != "null") out.add(value)
    }
    return out
}

/**
 * Reads exactly [slots] strings **in place**, keeping an empty slot empty.
 *
 * Gratitude needs this: the draft has to remember that the user filled the
 * third box and left the second one blank, so it must not be compacted the way
 * [stringList] compacts activities. The database column is a compacted array
 * (the web saves `gratitude.filter(g => g.trim())`), so reading it this way
 * simply pads the tail — the same thing the web renders.
 */
internal fun JSONArray?.slotList(slots: Int): List<String> {
    if (this == null) return List(slots) { "" }
    return (0 until slots).map { i ->
        if (isNull(i)) "" else optString(i, "").takeIf { it.isNotBlank() && it != "null" } ?: ""
    }
}

/** JSON object of booleans (the `habits` map). */
internal fun JSONObject?.booleanMap(): Map<String, Boolean> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, Boolean>()
    keys().forEach { key -> out[key] = optBoolean(key) }
    return out
}

/** JSON object of ints (the `scale_values` map). */
internal fun JSONObject?.intMap(): Map<String, Int> {
    if (this == null) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    keys().forEach { key -> out[key] = optInt(key, 0) }
    return out
}

/** `phone_top_apps` rows → [{app, minutes}]. */
internal fun JSONArray?.topApps(): List<PhoneTopApp> {
    if (this == null) return emptyList()
    val out = ArrayList<PhoneTopApp>(length())
    for (i in 0 until length()) {
        val row = optJSONObject(i) ?: continue
        val app = row.plainString("app").takeIf { it.isNotBlank() } ?: continue
        out.add(PhoneTopApp(app, row.optInt("minutes", 0)))
    }
    return out
}
