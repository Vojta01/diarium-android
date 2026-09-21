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
        val goals = decode(raw)
        // An icon lost to the old add row comes back on the next read, and the repair is
        // written once instead of being re-derived on every load.
        val repaired = repairLostIcons(goals)
        if (repaired != goals) save(repaired)
        return repaired
    }

    suspend fun save(goals: List<DailyGoal>) {
        dataStore.edit { prefs -> prefs[stringPreferencesKey(KEY)] = encode(goals) }
    }

    companion object {
        const val KEY = "diarium_goals"

        val DEFAULT_GOALS = listOf(DailyGoal(id = "1", emoji = "🏋️", name = "Krátké cvičení"))

        /**
         * The generic icon the old "Přidat cíl" row wrote into every new goal, before
         * there was any way to pick one.
         */
        const val PLACEHOLDER_EMOJI = "🎯"

        /**
         * Gives a goal back the icon it lost to the old add row.
         *
         * Until the picker existed, "Přidat cíl" hardcoded [PLACEHOLDER_EMOJI], so a goal
         * the owner deleted and typed again ("Krátké cvičení") came back wearing a target
         * even though that name has an icon of its own in [DEFAULT_GOALS]. The name is all
         * that survived, so the name is what the repair matches on — diacritics folded, so
         * "kratke cviceni" matches too.
         *
         * Only the placeholder is ever replaced: an icon the owner actually picked (any
         * other emoji, or a deliberately emptied one is left empty) is none of this
         * function's business.
         */
        fun repairLostIcons(goals: List<DailyGoal>): List<DailyGoal> = goals.map { goal ->
            val builtIn = DEFAULT_GOALS.firstOrNull { it.name.matchesLoosely(goal.name) }
            if (builtIn != null && goal.emoji == PLACEHOLDER_EMOJI) {
                goal.copy(emoji = builtIn.emoji)
            } else {
                goal
            }
        }

        /** Two goal names are the same word when case, spacing and diacritics are ignored. */
        private fun String.matchesLoosely(other: String): Boolean =
            folded() == other.folded()

        private fun String.folded(): String = java.text.Normalizer
            .normalize(trim(), java.text.Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase()

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
                val name = row.plainString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                DailyGoal(
                    id = row.plainString("id").ifBlank { name },
                    emoji = row.plainString("emoji"),
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
