package cz.digitalnivedomi.diarium.ui.settings

import java.util.Locale

/**
 * Pure JVM logic behind the "Nastavení notifikací" screen — no Android imports, so
 * all of it is unit tested on the JVM ([NotificationSettingsStateTest]).
 *
 * The screen keeps exactly the keys, labels and defaults the old AppCompat
 * `NotificationSettingsActivity` had, because prefs written by the alpha builds
 * must keep working (`NotificationPrefsStore` is untouched).
 *
 * Time convention (same as `NotificationPrefs`): minutes since midnight for times,
 * `Set<Int>` of 1..7 (Monday=1 … Sunday=7) for reminder days.
 */
object NotificationSettingsState {

    const val MINUTES_PER_DAY = 24 * 60

    /** "Po"…"Ne" — the chip labels, identical to the old Activity's `dayNames`. */
    val dayShortNames: List<String> = listOf("Po", "Út", "St", "Čt", "Pá", "So", "Ne")

    /** Full names used by the old day picker dialog. */
    val dayFullNames: List<String> =
        listOf("Pondělí", "Úterý", "Středa", "Čtvrtek", "Pátek", "Sobota", "Neděle")

    // ── Days ──────────────────────────────────────────────────────────────────

    /** Adds [day] when missing, removes it when present — `1..7`, never empty-checked. */
    fun toggleDay(days: Set<Int>, day: Int): Set<Int> =
        if (day in days) days - day else days + day

    /** Short label ("Út") for a 1..7 day; falls back to "?" outside the range. */
    fun weeklyDayLabel(day: Int, names: List<String> = dayShortNames): String =
        names.getOrElse(day - 1) { "?" }

    /** Full label ("Úterý") for a 1..7 day — the old dialog's vocabulary. */
    fun weeklyDayName(day: Int): String = weeklyDayLabel(day, dayFullNames)

    /** "Každý den" / "Žádný den" / "Po Út St" — the summary under the day chips. */
    fun daysSummary(days: Set<Int>, names: List<String> = dayShortNames): String = when {
        days.isEmpty() -> "Žádný den"
        days.size >= 7 -> "Každý den"
        else -> days.sorted().joinToString(" ") { weeklyDayLabel(it, names) }
    }

    // ── Time ──────────────────────────────────────────────────────────────────

    /** Wraps any minute count into 0..1439 (a stored value can never break the UI). */
    fun normalizedMinutes(minutes: Int): Int {
        val m = minutes % MINUTES_PER_DAY
        return if (m < 0) m + MINUTES_PER_DAY else m
    }

    /** "%02d:%02d" — the exact format the old screen used. */
    fun timeLabel(minutes: Int): String {
        val m = normalizedMinutes(minutes)
        return String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)
    }

    /** Hour part for the platform time picker's initial value. */
    fun hourOf(minutes: Int): Int = normalizedMinutes(minutes) / 60

    /** Minute part for the platform time picker's initial value. */
    fun minuteOf(minutes: Int): Int = normalizedMinutes(minutes) % 60

    // ── Channel ───────────────────────────────────────────────────────────────

    /**
     * The channel the reminder actually lands on — the same two names the worker
     * creates ("Diarium připomenutí" / "Diarium připomenutí (tichá)").
     */
    fun channelLabel(sound: Boolean): String =
        if (sound) "Diarium připomenutí" else "Diarium připomenutí (tichá)"

    // ── System permission rows ────────────────────────────────────────────────

    fun permissionChip(state: PermissionState): String = when (state) {
        PermissionState.GRANTED -> "povoleno"
        PermissionState.DENIED -> "zamítnuto"
        PermissionState.UNDECIDED -> "nevyřešeno"
    }

    fun notificationPermissionHint(state: PermissionState): String = when (state) {
        PermissionState.GRANTED -> "Diarium ti může posílat připomenutí."
        PermissionState.DENIED -> "Notifikace jsou v systému vypnuté — v Nastavení telefonu je zapni."
        PermissionState.UNDECIDED -> "Systém se ještě nezeptal. Povol notifikace v Nastavení telefonu."
    }

    fun usageAccessChip(state: UsageAccessState): String = when (state) {
        UsageAccessState.ALLOWED -> "povoleno"
        UsageAccessState.DENIED -> "zamítnuto"
        UsageAccessState.ERRORED -> "chyba"
        UsageAccessState.UNDECIDED -> "nevyřešeno"
    }

    /**
     * Why the row matters and what to do — the exact wording matters here, a greyed
     * out switch without an explanation looks like a broken screen (spec risk #1).
     */
    fun usageAccessHint(state: UsageAccessState): String = when (state) {
        UsageAccessState.ALLOWED ->
            "Čas na obrazovce se čte přímo z telefonu (jako Digitální rovnováha)."
        UsageAccessState.DENIED ->
            "Systém přístup zatím neumožnil. Otevři nastavení níže a přepínač u Diarium zapni — pak appku otevři znovu."
        UsageAccessState.ERRORED ->
            "Přístup se nepodařilo ověřit. Zapni přepínač v nastavení níže; kdyby zůstal šedý, přeinstaluj Diarium."
        UsageAccessState.UNDECIDED ->
            "Klikni na „Otevřít nastavení\" a povol Diariumu přístup k údajům o používání. " +
                "Kdyby přepínač zůstal šedý, povol ještě v App info → ⋮ → „Povolit omezená nastavení\"."
    }

    fun exactAlarmChip(allowed: Boolean): String = if (allowed) "povoleno" else "nepovoleno"

    fun exactAlarmHint(allowed: Boolean): String = if (allowed) {
        "Připomenutí může pípnout v přesnou minutu."
    } else {
        "Bez výjimky pro přesné alarmy může připomenutí dorazit o pár minut později."
    }
}
