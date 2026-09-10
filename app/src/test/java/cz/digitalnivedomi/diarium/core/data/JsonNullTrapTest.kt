package cz.digitalnivedomi.diarium.core.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "null" trap.
 *
 * PostgREST returns a SQL NULL column as JSON null. On Android, `JSONObject.optString`
 * turns that into the literal string "null" — which then renders on screen as the
 * word "null" (a screenshot showed it three times on the Dashboard: the mood chip,
 * the mood label and the note row). `.takeIf { it.isNotBlank() }` cannot catch it,
 * because "null" is not blank. Every read of a nullable text column therefore goes
 * through [stringOrNull] / [plainString], and this test pins that down on the JVM.
 *
 * Note on the runtime split: this JVM test uses `org.json:json` (a test dependency),
 * whose `optString` returns "" for JSON null; the Android runtime returns the literal
 * "null". [stringOrNull] guards both — `isNull(key)` for the JVM path and the
 * `!= "null"` check for the Android path — so the literal-`"null"` cases below are
 * the ones that actually regress if the guard is dropped.
 */
class JsonNullTrapTest {

    /** An `entries` row exactly as PostgREST shapes it, with SQL NULL columns. */
    private fun rowWithSqlNulls(): JSONObject = JSONObject(
        """
        {
          "date": "2026-09-10",
          "mood": 4,
          "mood_emoji": null,
          "sleep_quality": 3,
          "stress": 2,
          "activities": ["Sport", null, "Čtení"],
          "habits": {"alkohol": false},
          "gratitude": ["Rodina", null, "Práce"],
          "note": null,
          "photo_path": null,
          "scale_values": {"energy": 4},
          "weather": [null, "Slunečno"],
          "ai_reflection": null
        }
        """.trimIndent(),
    )

    @Test
    fun `a row with SQL NULL columns never yields the literal word null`() {
        val entry = EntriesRepository.fromRow(rowWithSqlNulls())

        // The three cells the screenshot showed "null" in.
        assertEquals("", entry.moodEmoji)
        assertEquals("", entry.note)
        assertNull(entry.photoPath)
        assertNull(entry.aiReflection)

        // No field anywhere in the model carries the literal "null".
        assertFalse(entry.moodEmoji == "null")
        assertFalse(entry.note == "null")
        assertTrue(entry.activities.none { it == "null" })
        assertTrue(entry.weather.none { it == "null" })

        // The real array values are preserved; JSON-null elements are skipped.
        assertEquals(listOf("Sport", "Čtení"), entry.activities)
        assertEquals(listOf("Slunečno"), entry.weather)

        // Gratitude keeps its slots in place; the null slot stays empty.
        assertEquals("Rodina", entry.gratitude[0])
        assertEquals("", entry.gratitude[1])
        assertEquals("Práce", entry.gratitude[2])
    }

    @Test
    fun `DiaryEntry fromJson maps camelCase JSON null back to absent`() {
        val json = JSONObject(
            """
            {
              "mood": 4,
              "moodEmoji": null,
              "sleepQuality": 3,
              "stress": 2,
              "activities": ["Sport", null],
              "habits": {"alkohol": false},
              "gratitude": ["Rodina", null, "Práce"],
              "note": null,
              "photoPath": null,
              "scaleValues": {"energy": 4},
              "weather": [null, "Slunečno"]
            }
            """.trimIndent(),
        )

        val entry = DiaryEntry.fromJson(json)

        assertEquals("", entry.moodEmoji)
        assertEquals("", entry.note)
        assertNull(entry.photoPath)
        assertFalse(entry.moodEmoji == "null")
        assertFalse(entry.note == "null")
        assertEquals(listOf("Sport"), entry.activities)
        assertEquals(listOf("Slunečno"), entry.weather)
    }

    @Test
    fun `a row whose columns hold the literal null string is normalised away`() {
        // What the Android org.json runtime produces for JSON null, and what a
        // legacy write of the string "null" would leave behind. On this JVM the
        // reference org.json returns "" for JSON null, so this is the case that
        // genuinely regresses if the "null" guard is dropped.
        val row = JSONObject(
            """
            {
              "mood": 4,
              "mood_emoji": "null",
              "note": "null",
              "photo_path": "null",
              "ai_reflection": "null",
              "activities": ["Sport", "null"],
              "weather": ["null"]
            }
            """.trimIndent(),
        )

        val entry = EntriesRepository.fromRow(row)

        assertEquals("", entry.moodEmoji)
        assertEquals("", entry.note)
        assertNull(entry.photoPath)
        assertNull(entry.aiReflection)
        assertEquals(listOf("Sport"), entry.activities)
        assertTrue(entry.weather.isEmpty())
    }

    @Test
    fun `stringOrNull treats JSON null, a missing key and the literal null as absent`() {
        val json = JSONObject().apply {
            put("nullValue", JSONObject.NULL)
            put("literal", "null")
            put("blank", "   ")
            put("real", "Dnes dobrý den")
        }

        assertNull(json.stringOrNull("nullValue"))
        assertNull(json.stringOrNull("missing"))
        assertNull(json.stringOrNull("literal"))
        assertNull(json.stringOrNull("blank"))
        assertEquals("Dnes dobrý den", json.stringOrNull("real"))

        // A null receiver is absent too.
        val absent: JSONObject? = null
        assertNull(absent.stringOrNull("anything"))

        // plainString is the drop-in: absent becomes "".
        assertEquals("", json.plainString("nullValue"))
        assertEquals("", json.plainString("missing"))
        assertEquals("", json.plainString("literal"))
        assertEquals("Dnes dobrý den", json.plainString("real"))
        assertEquals("", absent.plainString("anything"))
    }

    @Test
    fun `stringList and slotList drop null and the literal null elements`() {
        val array = JSONArray("""["Sport", null, "null", "  ", "Čtení"]""")

        assertEquals(listOf("Sport", "Čtení"), array.stringList())

        val slots = JSONArray("""["A", null, "null"]""")
        assertEquals(listOf("A", "", ""), slots.slotList(3))
        // Shorter than the slot count pads with blanks, as the web renders it.
        assertEquals(listOf("A", "", "", ""), slots.slotList(4))
    }

    @Test
    fun `real values still survive the null normalisation`() {
        val row = JSONObject(
            """
            {
              "date": "2026-09-10",
              "mood": 5,
              "mood_emoji": "🙂",
              "note": "Dnes dobrý den",
              "photo_path": "https://example.supabase.co/storage/v1/object/public/diary-photos/u/2026-09-10.jpg",
              "activities": ["Sport"],
              "weather": ["Slunečno"],
              "scale_values": {"energy": 4},
              "habits": {"alkohol": true},
              "gratitude": ["Rodina", "", "Práce"],
              "ai_reflection": "Dnes sis to užil."
            }
            """.trimIndent(),
        )

        val entry = EntriesRepository.fromRow(row)

        assertEquals(5, entry.mood)
        assertEquals("🙂", entry.moodEmoji)
        assertEquals("Dnes dobrý den", entry.note)
        assertEquals(
            "https://example.supabase.co/storage/v1/object/public/diary-photos/u/2026-09-10.jpg",
            entry.photoPath,
        )
        assertEquals("Dnes sis to užil.", entry.aiReflection)
        assertEquals(listOf("Sport"), entry.activities)
        assertEquals(listOf("Slunečno"), entry.weather)
        assertTrue(entry.habits["alkohol"] == true)
        assertEquals(4, entry.scaleValues["energy"])
    }
}
