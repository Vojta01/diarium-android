package cz.digitalnivedomi.diarium.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Note templates for the signed-in user (table `templates`).
 *
 * Every call goes out with the user's own JWT from [SessionContext] — the client
 * only ever carries the anon key plus that session token, never a server-side
 * key. Rows are always scoped by `user_id` so one account can never read or
 * write another's templates.
 *
 * Ordering is applied twice on purpose: the query asks for `sort_order` and the
 * result is sorted again client-side, so a backend that ignores the parameter
 * still gives the UI a stable order.
 *
 * [TemplateDefaults.DEFAULTS] is the single source of truth for the three
 * built-in templates — the UI's empty state and the JVM tests both read it, so
 * the names/contents/order can never drift apart.
 */
class TemplatesRepository(
    private val client: SupabaseClient,
    private val session: SessionContext = SessionContext(),
) {

    /** The user's templates, ordered by `sort_order` (never a fallback list). */
    suspend fun list(): List<TemplateItem> = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext emptyList()
        read("templates", mapOf("user_id" to "eq.$userId", "order" to "sort_order.asc"))
            .mapNotNull { row ->
                val content = row.plainString("content")
                if (content.isBlank()) return@mapNotNull null
                TemplateItem(
                    id = row.plainString("id"),
                    name = row.plainString("name").ifBlank { "Šablona" },
                    content = content,
                    sortOrder = row.optInt("sort_order", 0),
                    createdAt = row.plainString("created_at").takeIf { it.isNotBlank() },
                )
            }
            .sortedBy { it.sortOrder }
    }

    /** Creates a template at the end of the list. Blank input is refused, not stored. */
    suspend fun create(name: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val cleanName = name.trim()
        if (cleanName.isBlank() || content.isBlank()) return@withContext false
        val body = JSONObject().apply {
            put("user_id", userId)
            put("name", cleanName)
            put("content", content)
            put("sort_order", nextSortOrder())
        }
        client.post("templates", body).isSuccessful
    }

    /** Renames and/or rewrites a template; `sort_order` is left untouched. */
    suspend fun update(id: String, name: String, content: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        val cleanName = name.trim()
        if (id.isBlank() || cleanName.isBlank() || content.isBlank()) return@withContext false
        val body = JSONObject().apply {
            put("name", cleanName)
            put("content", content)
        }
        client.patch(
            "templates",
            body,
            mapOf("id" to "eq.$id", "user_id" to "eq.$userId"),
        ).isSuccessful
    }

    /** Deletes one template the user owns. */
    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        if (id.isBlank()) return@withContext false
        client.delete("templates", mapOf("id" to "eq.$id", "user_id" to "eq.$userId")).isSuccessful
    }

    /**
     * Inserts the three built-in templates for a user who has none. Idempotent:
     * an account that already has at least one template is left exactly as it is
     * (so a second tap can never duplicate the defaults).
     */
    suspend fun seedDefaults(): Boolean = withContext(Dispatchers.IO) {
        val userId = session.userId() ?: return@withContext false
        if (list().isNotEmpty()) return@withContext true
        TemplateDefaults.DEFAULTS.all { def ->
            val body = JSONObject().apply {
                put("user_id", userId)
                put("name", def.name)
                put("content", def.content)
                put("sort_order", def.sortOrder)
            }
            client.post("templates", body).isSuccessful
        }
    }

    // ── internals ───────────────────────────────────────────────────────────

    private suspend fun nextSortOrder(): Int =
        (list().maxOfOrNull { it.sortOrder } ?: 0) + 1

    private fun read(table: String, query: Map<String, String>): List<JSONObject> {
        val resp = client.get(table, query + mapOf("select" to "*"))
        if (!resp.isSuccessful) return emptyList()
        val array: JSONArray = resp.asJsonArray() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
}

/** One template row as the UI renders it. */
data class TemplateItem(
    val id: String,
    val name: String,
    val content: String,
    val sortOrder: Int = 0,
    /** `created_at` as returned by PostgREST (ISO-8601), null when absent. */
    val createdAt: String? = null,
)

/** The three built-ins, shared by the UI and the tests. */
data class TemplateDefault(
    val name: String,
    val content: String,
    val sortOrder: Int,
)

/** Mirrors `DEFAULT_TEMPLATES` in the web's `src/lib/templates.ts`, verbatim. */
object TemplateDefaults {

    val DEFAULTS: List<TemplateDefault> = listOf(
        TemplateDefault(
            name = "🌅 Ranní reflexe",
            content = "Dnes ráno se cítím...\n\n3 věci, na které se těším:\n1. \n2. \n3. \n\nCo dnes chci dokázat:",
            sortOrder = 1,
        ),
        TemplateDefault(
            name = "🌙 Večerní shrnutí",
            content = "Dnešek byl...\n\nNejlepší moment dne:\n\nCo bych zlepšil/a:\n\nZa co jsem vděčný/á:",
            sortOrder = 2,
        ),
        TemplateDefault(
            name = "💪 Těžký den",
            content = "Dnes to bylo těžké, protože...\n\nCo mě drží při životě:\n\nZítra bude líp, protože:",
            sortOrder = 3,
        ),
    )

    /** The same three defaults in the shape the check-in's picker consumes. */
    fun asNoteTemplates(): List<NoteTemplate> = DEFAULTS.map { def ->
        NoteTemplate(
            id = "default-${def.sortOrder}",
            name = def.name,
            content = def.content,
            sortOrder = def.sortOrder,
        )
    }
}

// ── Pure insert decision (unit-tested on the JVM) ────────────────────────────

/**
 * Inserting a template REPLACES the whole note. This function exists so the
 * "replace, never append" rule is proven by a test rather than by a comment: it
 * ignores [currentNote] entirely and returns exactly the template's content, so
 * no concatenation can ever leak in. The web's TemplatePicker behaves the same
 * way — appending would silently merge two different days' thoughts.
 */
internal fun applyTemplate(currentNote: String, templateContent: String): String = templateContent

/**
 * True when the note already holds real text, so the user must confirm before
 * it is thrown away. Whitespace-only notes count as empty — a note of spaces
 * has nothing worth keeping, and the web confirms on `text.trim()` too.
 */
internal fun needsConfirm(currentNote: String): Boolean = currentNote.isNotBlank()

/** First non-blank line of a template, clipped — the list's one-line preview. */
internal fun firstLinePreview(content: String): String {
    val line = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= 80) line else line.take(79).trimEnd() + "…"
}
