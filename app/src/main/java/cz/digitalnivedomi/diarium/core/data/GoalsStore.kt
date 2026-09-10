package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/** One daily goal; `completedDates` holds the ISO dates on which it was ticked. */
data class DailyGoal(
    val id: String,
    val emoji: String,
    val name: String,
    val completedDates: List<String> = emptyList(),
)

/**
 * Goals live on-device only (DataStore) — they mirror the web's
 * `diarium_goals` localStorage array, including the default "Krátké cvičení".
 */
class GoalsStore(private val dataStore: DataStore<Preferences>) {

    suspend fun load(): List<DailyGoal> {
        val raw = dataStore.data.first()[stringPreferencesKey(KEY)] ?: return DEFAULT_GOALS
        return decode(raw)
    }

    suspend fun save(goals: List<DailyGoal>) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(KEY)] = encode(goals) }
    }

    companion object {
        const val KEY = "diarium_goals"

        val DEFAULT_GOALS = listOf(DailyGoal(id = "1", emoji = "🏋️", name = "Krátké cvičení"))

        fun from(context: Context): GoalsStore = GoalsStore(context.goalsDataStore)

        fun encode(goals: List<DailyGoal>): String {
            val array = JSONArray()
            goals.forEach { goal ->
                array.put(
                    JSONObject().apply {
                        put("id", goal.id)
                        put("emoji", goal.emoji)
                        put("name", goal.name)
                        put("completedDates", JSONArray(goal.completedDates))
                    },
                )
            }
            return array.toString()
        }

        fun decode(raw: String): List<DailyGoal> = try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val name = row.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DailyGoal(
                    id = row.optString("id").ifBlank { name },
                    emoji = row.optString("emoji"),
                    name = name,
                    completedDates = row.optJSONArray("completedDates").stringList(),
                )
            }
        } catch (_: Exception) {
            DEFAULT_GOALS
        }
    }
}

private val Context.goalsDataStore by preferencesDataStore(name = "diarium_goals")
