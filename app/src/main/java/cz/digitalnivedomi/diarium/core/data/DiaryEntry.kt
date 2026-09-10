package cz.digitalnivedomi.diarium.core.data

import org.json.JSONArray
import org.json.JSONObject

/** One app used most on the phone that day (mirrored read-only from the worker). */
data class PhoneTopApp(val app: String, val minutes: Int)

/**
 * One day's check-in — the native twin of the web `CheckInData` / `EMPTY_DATA`
 * shape in `src/components/OnePageCheckIn.tsx`.
 *
 * Field names are Kotlin-idiomatic here and translated to the snake_case the
 * `save_daily_entry(jsonb)` RPC expects only at the edge
 * ([EntriesRepository.buildPayload]) — so the whole UI works with one model and
 * the wire format lives in exactly one place.
 *
 * The last four fields are filled by the screen-time sync worker, never by the
 * form. They are read back for the read-only "Screen time" row and are
 * deliberately not part of the save payload.
 */
data class DiaryEntry(
    val mood: Int = 0,
    val moodEmoji: String = "",
    val sleepQuality: Int = 0,
    val stress: Int = 0,
    val activities: List<String> = emptyList(),
    val habits: Map<String, Boolean> = emptyMap(),
    val gratitude: List<String> = listOf("", "", ""),
    val note: String = "",
    val photoPath: String? = null,
    val scaleValues: Map<String, Int> = emptyMap(),
    val weather: List<String> = emptyList(),
    val phoneScreenTime: Int? = null,
    val phoneUnlocks: Int? = null,
    val phoneTopApps: List<PhoneTopApp> = emptyList(),
) {

    /** Blank gratitude slots are dropped before sending, exactly like the web. */
    fun filteredGratitude(): List<String> = gratitude.filter { it.isNotBlank() }

    /** Only scales the user actually touched (value > 0) get mirrored. */
    fun positiveScaleValues(): Map<String, Int> =
        scaleValues.filterValues { it > 0 }.toSortedMap()

    /** True once the user has entered anything worth keeping as a draft. */
    fun hasContent(): Boolean =
        mood > 0 || sleepQuality > 0 || stress > 0 ||
            activities.isNotEmpty() || habits.values.any { it } ||
            note.isNotBlank() || gratitude.any { it.isNotBlank() } ||
            scaleValues.values.any { it > 0 } || !photoPath.isNullOrBlank() ||
            weather.isNotEmpty()

    /**
     * camelCase JSON — the draft blob persisted under `diarium_draft_{date}`,
     * matching the web's localStorage shape so drafts stay interchangeable.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("mood", mood)
        put("moodEmoji", moodEmoji)
        put("sleepQuality", sleepQuality)
        put("stress", stress)
        put("activities", JSONArray(activities))
        put("habits", JSONObject(habits))
        put("gratitude", JSONArray(gratitude))
        put("note", note)
        if (!photoPath.isNullOrBlank()) put("photoPath", photoPath)
        put("scaleValues", JSONObject(scaleValues))
        put("weather", JSONArray(weather))
    }

    companion object {
        /** Number of gratitude slots the form renders (web: three). */
        const val GRATITUDE_SLOTS = 3

        val EMPTY = DiaryEntry()

        fun fromJson(raw: String): DiaryEntry? = try {
            fromJson(JSONObject(raw))
        } catch (_: Exception) {
            null
        }

        fun fromJson(json: JSONObject): DiaryEntry = DiaryEntry(
            mood = json.optInt("mood", 0),
            moodEmoji = json.optString("moodEmoji"),
            sleepQuality = json.optInt("sleepQuality", 0),
            stress = json.optInt("stress", 0),
            activities = json.optJSONArray("activities").stringList(),
            habits = json.optJSONObject("habits").booleanMap(),
            gratitude = json.optJSONArray("gratitude").slotList(GRATITUDE_SLOTS),
            note = json.optString("note"),
            photoPath = json.optString("photoPath").takeIf { it.isNotBlank() },
            scaleValues = json.optJSONObject("scaleValues").intMap(),
            weather = json.optJSONArray("weather").stringList(),
        )
    }
}
