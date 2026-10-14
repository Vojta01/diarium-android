package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.digitalnivedomi.diarium.auth.SessionStore
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.net.URLDecoder

/**
 * The habit list's reads, pinned to the schema the web actually has.
 *
 * The bug this guards against: the repository used to query a table named `habits`
 * with `user_id=eq.<me>` — a table that does not exist in production — so the
 * request 404'd and the check-in showed only the static "Alkohol" fallback. The
 * real schema is `habit_catalog` (the global defaults, `is_default` rows) plus
 * `user_habits` (the user's own rows), which is what the web's `getHabits()` reads.
 *
 * The transport is faked (no network) and the session is a real Robolectric-backed
 * [SessionStore], so the requests asserted here are the ones the app would send.
 * Requests that are not the two habit tables get a 404, mirroring what the
 * nonexistent `habits` table returned in production — so a regression to that table
 * would show up as an empty/fallback list, not just a missing assertion.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PickersRepositoryHabitsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val sent = mutableListOf<HttpRequest>()
    private var catalog: String = "[]"
    private var userRows: String = "[]"

    private val transport = HttpTransport { request ->
        sent += request
        when {
            request.url.startsWith("$BASE_URL/habit_catalog?") -> HttpResponse(200, catalog)
            request.url.startsWith("$BASE_URL/user_habits?") -> HttpResponse(200, userRows)
            // Anything else — notably the old `habits` table — is a 404, as in prod.
            else -> HttpResponse(404, "[]")
        }
    }

    @Test
    fun `habits are the catalogue defaults plus the user's active customs`() = runTest {
        catalog = habitCatalog()
        userRows = userHabitRows()

        val habits = repository().habits()

        assertEquals(
            listOf("alkohol", "koureni", "studena_sprcha", "bez_kafe"),
            habits.map { it.key },
        )
        assertEquals("default", habits.first { it.key == "alkohol" }.source)
        assertTrue(habits.first { it.key == "alkohol" }.isNegative)
        assertEquals("user", habits.first { it.key == "koureni" }.source)
    }

    @Test
    fun `the nonexistent habits table is never queried`() = runTest {
        catalog = habitCatalog()
        userRows = userHabitRows()

        repository().habits()

        val urls = sent.map { it.url }
        assertTrue(urls.any { it.startsWith("$BASE_URL/habit_catalog?") })
        assertTrue(urls.any { it.startsWith("$BASE_URL/user_habits?") })
        // The exact path, so `user_habits` does not satisfy the check.
        assertFalse("habits table must not be read", urls.any { it.startsWith("$BASE_URL/habits?") })
    }

    @Test
    fun `catalogue defaults are the is_default rows, ordered by sort_order`() = runTest {
        catalog = JSONArray()
            .put(defaultRow("spanek", "Spánek", sortOrder = 2))
            .put(defaultRow("alkohol", "Alkohol", sortOrder = 1))
            .put(
                JSONObject()
                    .put("key", "cviceni").put("label", "Cvičení")
                    .put("is_default", false).put("sort_order", 3),
            )
            .toString()

        val habits = repository().habits()

        assertEquals(listOf("alkohol", "spanek"), habits.map { it.key })
        val query = decoded(sent.first { it.url.startsWith("$BASE_URL/habit_catalog?") })
        assertTrue(query.contains("is_default=eq.true"))
        assertTrue(query.contains("select=*"))
    }

    @Test
    fun `user_habits are read for the signed-in user with no is_active filter`() = runTest {
        catalog = habitCatalog()
        userRows = userHabitRows()

        repository().habits()

        val query = decoded(sent.first { it.url.startsWith("$BASE_URL/user_habits?") })
        assertTrue(query.contains("user_id=eq.$USER_ID"))
        // The web reads every row and filters client-side; the read itself is unfiltered.
        assertFalse(query.contains("is_active"))
    }

    @Test
    fun `an active user row overrides a default and an inactive one stays hidden`() = runTest {
        catalog = habitCatalog()
        userRows = JSONArray()
            .put(userRow("u1", "koureni", "Kouření", active = true))
            // Inactive rows are hide-overrides, not visible habits.
            .put(userRow("u2", "test_cviceni", "Test cvičení", active = false))
            // The icon editor's row on a default's key: the catalogue keeps the slot, the
            // owner's name/icon/flag win — otherwise an edit would revert on every read.
            .put(
                userRow("u3", "alkohol", "Alkohol override", active = true)
                    .put("icon", "🍷")
                    .put("is_negative", false),
            )
            .toString()

        val habits = repository().habits()

        assertEquals(listOf("alkohol", "koureni"), habits.map { it.key })
        val alkohol = habits.first { it.key == "alkohol" }
        assertEquals("Alkohol override", alkohol.label)
        assertEquals("🍷", alkohol.icon)
        assertFalse("the override's negative flag wins too", alkohol.isNegative)
        // What the editor never touched stays the catalogue's.
        assertEquals("zdraví", alkohol.category)
        assertEquals("#ef4444", alkohol.color)
        assertEquals("default", alkohol.source)
        assertFalse(habits.any { it.key == "test_cviceni" })
    }

    @Test
    fun `an override with no icon of its own keeps the catalogue icon`() = runTest {
        catalog = habitCatalog()
        userRows = JSONArray()
            .put(userRow("u1", "alkohol", "Alkohol (nově)", active = true))
            .toString()

        val alkohol = repository().habits().first { it.key == "alkohol" }

        assertEquals("Alkohol (nově)", alkohol.label)
        // A row that only renamed the habit must not blank its icon.
        assertEquals("🍺", alkohol.icon)
    }

    @Test
    fun `a signed-out session reads only the defaults`() = runTest {
        catalog = habitCatalog()

        val habits = repository(SessionStore(context).apply { clear() }).habits()

        assertEquals(listOf("alkohol"), habits.map { it.key })
        assertFalse(sent.any { it.url.startsWith("$BASE_URL/user_habits?") })
    }

    @Test
    fun `an inactive row on a default's key hides that default`() = runTest {
        catalog = JSONArray()
            .put(defaultRow("alkohol", "Alkohol", 1))
            .put(defaultRow("koureni", "Kouření", 2))
            .toString()
        userRows = JSONArray()
            // The wizard's "switched off" write for a standard habit: the guide looked
            // like it worked, yet the default kept showing in every picker.
            .put(userRow("u1", "koureni", "Kouření", active = false))
            .toString()

        val habits = repository().habits()

        assertEquals(listOf("alkohol"), habits.map { it.key })
    }

    @Test
    fun `a hidden default disappears while active customs stay`() = runTest {
        catalog = JSONArray()
            .put(defaultRow("alkohol", "Alkohol", 1))
            .put(defaultRow("koureni", "Kouření", 2))
            .toString()
        userRows = JSONArray()
            .put(userRow("u1", "koureni", "Kouření", active = false))
            // An inactive *custom* (not a default key) simply never shows.
            .put(userRow("u2", "porno", "Porno", active = false))
            .put(userRow("u3", "studena_sprcha", "Studená sprcha", active = true))
            .toString()

        val habits = repository().habits()

        assertEquals(listOf("alkohol", "studena_sprcha"), habits.map { it.key })
    }

    @Test
    fun `an empty read degrades to the static fallback`() = runTest {
        catalog = "[]"
        userRows = "[]"

        assertEquals(PickerDefaults.HABIT_FALLBACK, repository().habits())
    }

    @Test
    fun `the habit reads carry the user's own token, never a server key`() = runTest {
        catalog = habitCatalog()
        userRows = userHabitRows()

        repository().habits()

        sent.forEach { request ->
            assertEquals("Bearer jwt-access-token", request.headers["Authorization"])
            assertEquals("anon-test-key", request.headers["apikey"])
        }
        val flat = sent.joinToString(" ") { "${it.url} ${it.headers} ${it.body}" }.lowercase()
        assertFalse(flat.contains("service_role"))
        assertFalse(flat.contains("sb_secret"))
    }

    // ── rename / re-icon (upsert) ───────────────────────────────────────────

    @Test
    fun `updateHabit upserts the renamed habit into user_habits`() = runTest {
        val saved = repository().updateHabit(
            key = "alkohol",
            label = "  Alkohol (nově)  ",
            icon = "🍷",
            isNegative = true,
        )

        assertTrue(saved)
        // The call reads the row first (so a custom habit's colour survives an edit) and
        // then writes the upsert — the write is what this test is about.
        val request = sent.single { it.method == "POST" }
        val read = decoded(sent.single { it.method == "GET" })
        assertTrue("the read targets the edited row", read.contains("key=eq.alkohol"))
        assertEquals("POST", request.method)
        assertTrue(request.url.startsWith("$BASE_URL/user_habits?"))
        // The conflict target that makes a second call an UPDATE, not a 409.
        assertEquals("on_conflict=user_id,key", decoded(request))
        assertEquals("resolution=merge-duplicates", request.headers["Prefer"])
        val body = JSONObject(request.body!!)
        assertEquals(USER_ID, body.getString("user_id"))
        // The key is written through as given — it joins the day's entries.
        assertEquals("alkohol", body.getString("key"))
        assertEquals("Alkohol (nově)", body.getString("label"))
        assertEquals("🍷", body.getString("icon"))
        assertEquals("#6366F1", body.getString("color"))
        assertTrue(body.getBoolean("is_negative"))
        assertTrue(body.getBoolean("is_active"))
    }

    // ── add / remove (management) ───────────────────────────────────────────

    @Test
    fun `addHabit upserts a new custom habit with the slugged key`() = runTest {
        val habit = requireNotNull(
            repository().addHabit(label = "  Jít běhat!  ", icon = ""),
        ) { "addHabit must return the new habit" }

        // The returned def is the row as written, ready to append to the grid.
        assertEquals("jit_behat", habit.key)
        assertEquals("Jít běhat!", habit.label)
        assertEquals("✅", habit.icon)
        assertEquals("vlastní", habit.category)
        assertEquals("#6366F1", habit.color)
        assertFalse(habit.isNegative)
        assertEquals("user", habit.source)
        assertTrue(habit.isActive)

        val request = sent.single { it.method == "POST" }
        assertTrue(request.url.startsWith("$BASE_URL/user_habits?"))
        // The same conflict target every habit write uses: a retry cannot duplicate.
        assertEquals("on_conflict=user_id,key", decoded(request))
        assertEquals("resolution=merge-duplicates", request.headers["Prefer"])
        val body = JSONObject(request.body!!)
        assertEquals(USER_ID, body.getString("user_id"))
        // The label keeps its punctuation, only the key is a slug.
        assertEquals("jit_behat", body.getString("key"))
        assertEquals("Jít běhat!", body.getString("label"))
        // A blank icon becomes the web's default rather than an empty string.
        assertEquals("✅", body.getString("icon"))
        assertEquals("vlastní", body.getString("category"))
        assertEquals("#6366F1", body.getString("color"))
        assertFalse(body.getBoolean("is_negative"))
        assertTrue(body.getBoolean("is_active"))
    }

    @Test
    fun `addHabit suffixes a slug that is already taken`() = runTest {
        // Same key, different label: only the key collides, so the newcomer is
        // suffixed instead of merging onto the existing row. (A *label* collision is
        // its own case — the table's case-insensitive label index would refuse it.)
        userRows = JSONArray()
            .put(userRow("u1", "jit_behat", "Běh", active = true))
            .toString()

        val habit = requireNotNull(repository().addHabit(label = "Jít běhat", icon = "🏃")) {
            "addHabit must return the new habit"
        }

        // The existing row keeps its key; the newcomer moves to the next free suffix.
        assertEquals("jit_behat_2", habit.key)
        val body = JSONObject(sent.single { it.method == "POST" }.body!!)
        assertEquals("jit_behat_2", body.getString("key"))
    }

    @Test
    fun `addHabit returns the existing row when the label is already taken`() = runTest {
        userRows = JSONArray()
            .put(userRow("u1", "porno", "Porno", active = true))
            .toString()

        val habit = requireNotNull(repository().addHabit(label = "  porno  ", icon = "")) {
            "addHabit must resolve to the existing row instead of failing"
        }

        // The account's own row wins: its key and label, and nothing is written.
        assertEquals("porno", habit.key)
        assertEquals("Porno", habit.label)
        assertEquals("user", habit.source)
        assertTrue(habit.isActive)
        assertFalse("no write may be sent for an already-live habit", sent.any { it.method == "POST" })
    }

    @Test
    fun `addHabit switches a hidden habit back on and keeps its look`() = runTest {
        userRows = JSONArray()
            .put(userRow("u1", "masturb5", "masturb5", active = false))
            .toString()

        val habit = requireNotNull(repository().addHabit(label = "MASTURB5", icon = "✅"))

        assertEquals("masturb5", habit.key)
        assertTrue(habit.isActive)
        // Reactivation is the same read-then-upsert every habit write uses.
        val request = sent.single { it.method == "POST" }
        assertTrue(request.url.startsWith("$BASE_URL/user_habits?"))
        assertEquals("on_conflict=user_id,key", decoded(request))
        val body = JSONObject(request.body!!)
        assertEquals("masturb5", body.getString("key"))
        assertTrue(body.getBoolean("is_active"))
    }

    @Test
    fun `addHabit refuses a blank label before any write`() = runTest {
        val added = repository().addHabit(label = "   ", icon = "🏃")

        assertNull(added)
        // Nothing may reach the database on behalf of an unnamed habit.
        assertFalse("no POST may be sent", sent.any { it.method == "POST" })
    }

    @Test
    fun `removeHabit hides the habit with an is_active false upsert`() = runTest {
        val saved = repository().removeHabit(
            HabitDef(
                key = "alkohol",
                label = "Alkohol",
                icon = "🍺",
                category = "zdraví",
                color = "#ef4444",
                isNegative = true,
                source = "default",
            ),
        )

        assertTrue(saved)
        // Same shape as updateHabit: the current row is read first, then upserted.
        val request = sent.single { it.method == "POST" }
        val read = decoded(sent.single { it.method == "GET" })
        assertTrue("the read targets the removed row", read.contains("key=eq.alkohol"))
        assertTrue(request.url.startsWith("$BASE_URL/user_habits?"))
        assertEquals("on_conflict=user_id,key", decoded(request))
        assertEquals("resolution=merge-duplicates", request.headers["Prefer"])
        val body = JSONObject(request.body!!)
        assertEquals(USER_ID, body.getString("user_id"))
        assertEquals("alkohol", body.getString("key"))
        // The habit is hidden as itself: label, icon and flag survive the removal.
        assertEquals("Alkohol", body.getString("label"))
        assertEquals("🍺", body.getString("icon"))
        assertEquals("zdraví", body.getString("category"))
        assertEquals("#ef4444", body.getString("color"))
        assertTrue(body.getBoolean("is_negative"))
        // "Removal" is a hide, so past days keep their ticks and the row is never
        // deleted.
        assertFalse(body.getBoolean("is_active"))
        assertFalse(sent.any { it.method == "DELETE" })
    }

    @Test
    fun `deleteHabit removes the row outright`() = runTest {
        assertTrue(repository().deleteHabit("masturb5"))

        val delete = sent.single { it.method == "DELETE" }
        assertTrue(delete.url.startsWith("$BASE_URL/user_habits?"))
        val query = decoded(delete)
        assertTrue(query.contains("user_id=eq.$USER_ID"))
        assertTrue(query.contains("key=eq.masturb5"))
        // A true delete carries no body: the row is gone, not hidden.
        assertTrue(delete.body == null)
    }

    private fun decoded(request: HttpRequest): String =
        URLDecoder.decode(request.url.substringAfter('?'), Charsets.UTF_8.name())

    private fun repository(store: SessionStore = signedInStore()): PickersRepository {
        val client = SupabaseClient(
            sessionStore = store,
            transport = transport,
            baseUrl = BASE_URL,
            anonKey = "anon-test-key",
        )
        return PickersRepository(client, SessionContext(store))
    }

    /** A signed-in session; a cleared one means "before login / after logout". */
    private fun signedInStore(): SessionStore = SessionStore(context).apply {
        save(
            JSONObject().apply {
                put("access_token", "jwt-access-token")
                put("refresh_token", "refresh-1")
                put("expires_at", System.currentTimeMillis() / 1000 + 3600)
                put("user", JSONObject().put("id", USER_ID))
            },
        )
    }

    /** The production catalogue: a single default, `Alkohol`. */
    private fun habitCatalog(): String = JSONArray().put(defaultRow("alkohol", "Alkohol", 1)).toString()

    private fun defaultRow(key: String, label: String, sortOrder: Int): JSONObject = JSONObject()
        .put("key", key)
        .put("label", label)
        .put("icon", if (key == "alkohol") "🍺" else "")
        .put("category", "zdraví")
        .put("color", "#ef4444")
        .put("is_negative", key == "alkohol")
        .put("is_default", true)
        .put("sort_order", sortOrder)

    /** The owner's real rows: three active customs and one inactive hide-override. */
    private fun userHabitRows(): String = JSONArray()
        .put(userRow("u1", "koureni", "Kouření", active = true))
        .put(userRow("u2", "studena_sprcha", "Studená sprcha", active = true))
        .put(userRow("u3", "bez_kafe", "Bez kávy", active = true))
        .put(userRow("u4", "test_cviceni", "Test cvičení", active = false))
        .toString()

    private fun userRow(id: String, key: String, label: String, active: Boolean): JSONObject =
        JSONObject()
            .put("id", id)
            .put("user_id", USER_ID)
            .put("key", key)
            .put("label", label)
            .put("icon", "")
            .put("is_active", active)

    private companion object {
        const val USER_ID = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val BASE_URL = "https://example.supabase.co/rest/v1"
    }
}
