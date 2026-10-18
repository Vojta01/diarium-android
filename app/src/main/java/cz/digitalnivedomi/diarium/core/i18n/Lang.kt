package cz.digitalnivedomi.diarium.core.i18n

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two UI languages Diarium ships with. */
enum class Lang(val code: String) {
    CS("cs"),
    EN("en"),
}

/**
 * The app-wide UI language.
 *
 * Kept in plain SharedPreferences (not DataStore) so [init] can read it synchronously in
 * `MainActivity.onCreate` — before the first composition, so the very first frame is already
 * in the right language. [current] is Compose state: switching it recomposes the whole UI and
 * every `Text` wrapper re-translates.
 *
 * Scope: UI chrome only. Journal entries, notes and AI output stay in the language they were
 * written in; the language is never applied to user data.
 */
object AppLanguage {
    private const val PREFS = "diarium_language"
    private const val KEY = "lang"

    private var started = false

    var current: Lang by mutableStateOf(Lang.CS)
        private set

    fun init(context: Context) {
        if (started) return
        started = true
        val saved = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null)
        current = Lang.values().firstOrNull { it.code == saved } ?: Lang.CS
    }

    fun set(context: Context, lang: Lang) {
        current = lang
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, lang.code)
            .apply()
    }

    fun isEnglish(): Boolean = current == Lang.EN
}
