package cz.digitalnivedomi.diarium.core.data

/**
 * Does this day count as a *record*?
 *
 * The owner's rule (2026-09-11): "if there is no mood filled in, the record is
 * missing — screen time and unlock count are filled in automatically." The phone
 * sync creates an `entries` row for every synced day, so a row alone says nothing
 * about whether the owner actually journaled that day: 2026-09-07 carries 5 h 58 min
 * of screen time and 196 unlocks, yet no mood, sleep, stress, note or scales.
 *
 * A day therefore counts only when the *owner* filled something in, and the single
 * field that is never written by an automation is `mood`. Hence: mood present
 * (1..5) = record, otherwise the day reads as "still missing".
 *
 * Intentional deviation from the web, which counts any row: the web would show
 * 2026-09-07 as a day with a record, this app shows it as missing. Documented on
 * purpose — see the runbook, "days the phone synced but nobody journaled".
 */
internal fun isRecordedDay(mood: Int?): Boolean = mood != null && mood > 0
